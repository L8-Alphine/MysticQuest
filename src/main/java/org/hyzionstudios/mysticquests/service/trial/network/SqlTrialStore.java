package org.hyzionstudios.mysticquests.service.trial.network;

import org.hyzionstudios.mysticquests.api.trial.QuestTrials;
import org.hyzionstudios.mysticquests.api.trial.TrialNetworkStatus;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig.NetworkTrialsConfig;

import javax.annotation.Nullable;
import java.sql.Connection;
import java.sql.Driver;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Types;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Properties;
import java.util.UUID;

/**
 * The shared-SQL trial store (MysticRPG PO addendum 2026-10-24, R-1): runs, handoffs and results of
 * every server of one realm in one database, so a trial run can follow its player to another server.
 *
 * <p><b>Fencing.</b> Every row of {@code trial_runs} carries a {@code revision}. Each write is a
 * compare-and-set on the revision the writer last saw (and, for the owner's own writes, on
 * {@code owner_server}); it raises the revision by one. A server whose write matches nothing has been
 * fenced out: another server took the run over, and the loser drops its copy. One server runs a trial
 * at a time.
 *
 * <p><b>Realm scope.</b> Every query names the configured realm; a run, a slot, a result or a server
 * row of another realm is never read or written.
 *
 * <p>Synchronous JDBC on one connection, reopened when it breaks. Not thread-safe: the trial service
 * calls it from its single store thread only, never from a world thread.
 */
public final class SqlTrialStore implements AutoCloseable {

    /** Run states. */
    public static final String ACTIVE = "ACTIVE";
    public static final String SUSPENDED = "SUSPENDED";

    /** A row of {@code trial_runs}. */
    public record RunRow(String completionId, String realmId, UUID playerId, String trialId, String questId,
                         String questVersion, String state, @Nullable String ownerServer, long leaseUntil,
                         long handoffUntil, long revision, Instant startedAt, int deaths, String progress) {
    }

    /** A row of {@code trial_results}. */
    public record ResultRow(String completionId, String evidenceId, String realmId, UUID playerId, String trialId,
                            String questId, String questVersion, TrialStatus status, double score, boolean ranked,
                            long elapsedMillis, int deaths, String metrics, @Nullable Instant startedAt,
                            Instant resolvedAt, String reason, long causalRevision, String resolvedBy,
                            String receipt) {
    }

    /** The answer to a claim. */
    public record Claim(boolean claimed, long revision, String reason) {
    }

    private final NetworkTrialsConfig settings;
    private final TrialDialect dialect;
    private final TrialStoreSchema schema;
    private final String realm;
    private final String server;
    private final String runs;
    private final String slots;
    private final String results;
    private final String servers;

    @Nullable
    private Connection connection;
    private int schemaVersion;

    public SqlTrialStore(NetworkTrialsConfig settings) {
        this.settings = settings;
        this.dialect = TrialDialect.of(settings.jdbcUrl())
                .orElseThrow(() -> new IllegalArgumentException("unsupported trial store URL " + settings.jdbcUrl()));
        this.schema = new TrialStoreSchema(dialect, settings.tablePrefix());
        this.realm = settings.realmId();
        this.server = settings.serverId();
        this.runs = schema.table("trial_runs");
        this.slots = schema.table("trial_slots");
        this.results = schema.table("trial_results");
        this.servers = schema.table("trial_servers");
    }

    public TrialDialect dialect() {
        return dialect;
    }

    public TrialStoreSchema schema() {
        return schema;
    }

    public String realmId() {
        return realm;
    }

    public String serverId() {
        return server;
    }

    public int schemaVersion() {
        return schemaVersion;
    }

    /**
     * Connects and brings the schema up to date ({@code migration: apply}), or only reads its version
     * and returns the statements that would run ({@code migration: dry-run}).
     *
     * @return the dry-run statements; empty after an apply
     */
    public List<String> open() throws SQLException {
        Connection open = connection();
        if ("dry-run".equals(settings.migration())) {
            int current = schema.currentVersion(open);
            schemaVersion = current;
            return schema.dryRun(current);
        }
        schemaVersion = schema.apply(open);
        return List.of();
    }

    // ------------------------------------------------------------------ servers

    /**
     * Records this server's heartbeat and reads the realm's servers.
     *
     * @return every server of the realm with its last heartbeat (epoch milliseconds)
     */
    public Map<String, Long> heartbeat(long now) throws SQLException {
        Connection open = connection();
        try (PreparedStatement update = open.prepareStatement("UPDATE " + servers
                + " SET heartbeat_at = ?, contract_version = ?, schema_version = ? WHERE realm_id = ? AND server_id = ?")) {
            update.setLong(1, now);
            update.setInt(2, QuestTrials.API_VERSION);
            update.setInt(3, schemaVersion);
            update.setString(4, realm);
            update.setString(5, server);
            if (update.executeUpdate() == 0) {
                try (PreparedStatement insert = open.prepareStatement("INSERT INTO " + servers
                        + " (realm_id, server_id, contract_version, schema_version, started_at, heartbeat_at) VALUES (?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, realm);
                    insert.setString(2, server);
                    insert.setInt(3, QuestTrials.API_VERSION);
                    insert.setInt(4, schemaVersion);
                    insert.setLong(5, now);
                    insert.setLong(6, now);
                    insert.executeUpdate();
                } catch (SQLException raced) {
                    if (!isDuplicate(raced)) {
                        throw raced;
                    }
                }
            }
        }
        Map<String, Long> members = new LinkedHashMap<>();
        try (PreparedStatement select = open.prepareStatement("SELECT server_id, heartbeat_at, schema_version FROM "
                + servers + " WHERE realm_id = ?")) {
            select.setString(1, realm);
            try (ResultSet rows = select.executeQuery()) {
                while (rows.next()) {
                    // A server on another schema version cannot continue this server's runs.
                    if (rows.getInt(3) == TrialNetworkStatus.SCHEMA_VERSION) {
                        members.put(rows.getString(1), rows.getLong(2));
                    }
                }
            }
        }
        return members;
    }

    /** Removes this server from the realm's members (a clean stop), so no transfer targets it. */
    public void leave() throws SQLException {
        try (PreparedStatement delete = connection().prepareStatement("DELETE FROM " + servers
                + " WHERE realm_id = ? AND server_id = ?")) {
            delete.setString(1, realm);
            delete.setString(2, server);
            delete.executeUpdate();
        }
    }

    // ------------------------------------------------------------------ runs

    /**
     * Registers a new run owned by this server: its slot (one run per realm, player and trial) and its
     * row, in one transaction.
     */
    public Claim claim(String completionId, UUID player, String trialId, String questId, String questVersion,
                       Instant startedAt, long now) throws SQLException {
        Connection open = connection();
        boolean auto = open.getAutoCommit();
        open.setAutoCommit(false);
        try {
            try (PreparedStatement slot = open.prepareStatement("INSERT INTO " + slots
                    + " (realm_id, player_id, trial_id, completion_id) VALUES (?, ?, ?, ?)")) {
                slot.setString(1, realm);
                slot.setString(2, player.toString());
                slot.setString(3, trialId);
                slot.setString(4, completionId);
                slot.executeUpdate();
            } catch (SQLException taken) {
                open.rollback();
                if (isDuplicate(taken)) {
                    return new Claim(false, 0L, "this player already has the trial " + trialId
                            + " in progress in realm " + realm + " (" + slotHolder(player, trialId) + ")");
                }
                throw taken;
            }
            try (PreparedStatement insert = open.prepareStatement("INSERT INTO " + runs
                    + " (completion_id, realm_id, player_id, trial_id, quest_id, quest_version, run_state, owner_server, "
                    + "lease_until, handoff_until, revision, started_at, deaths, progress, updated_at) "
                    + "VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                insert.setString(1, completionId);
                insert.setString(2, realm);
                insert.setString(3, player.toString());
                insert.setString(4, trialId);
                insert.setString(5, questId);
                insert.setString(6, questVersion == null ? "" : questVersion);
                insert.setString(7, ACTIVE);
                insert.setString(8, server);
                insert.setLong(9, now + settings.leaseSeconds() * 1000L);
                insert.setLong(10, 0L);
                insert.setLong(11, 1L);
                insert.setString(12, startedAt.toString());
                insert.setInt(13, 0);
                insert.setNull(14, Types.VARCHAR);
                insert.setLong(15, now);
                insert.executeUpdate();
            }
            open.commit();
            return new Claim(true, 1L, "");
        } catch (SQLException failure) {
            safeRollback(open);
            throw failure;
        } finally {
            open.setAutoCommit(auto);
        }
    }

    private String slotHolder(UUID player, String trialId) {
        try (PreparedStatement select = connection().prepareStatement("SELECT r.run_state, r.owner_server FROM " + slots
                + " s JOIN " + runs + " r ON r.completion_id = s.completion_id WHERE s.realm_id = ? AND s.player_id = ? "
                + "AND s.trial_id = ?")) {
            select.setString(1, realm);
            select.setString(2, player.toString());
            select.setString(3, trialId);
            try (ResultSet rows = select.executeQuery()) {
                if (rows.next()) {
                    String owner = rows.getString(2);
                    return SUSPENDED.equals(rows.getString(1)) ? "waiting for you to continue it"
                            : "running on " + (owner == null ? "another server" : owner);
                }
            }
        } catch (SQLException ignored) {
            // Only decorates a refusal.
        }
        return "held by another run";
    }

    /**
     * The owner's checkpoint: renews the lease and saves deaths and progress.
     *
     * @return the new revision, or -1 when this server no longer owns the run at that revision (fenced)
     */
    public long checkpoint(String completionId, long expectedRevision, int deaths, @Nullable String progress, long now)
            throws SQLException {
        try (PreparedStatement update = connection().prepareStatement("UPDATE " + runs
                + " SET lease_until = ?, deaths = ?, progress = COALESCE(?, progress), revision = ?, updated_at = ? "
                + "WHERE completion_id = ? AND realm_id = ? AND owner_server = ? AND revision = ? AND run_state = ?")) {
            update.setLong(1, now + settings.leaseSeconds() * 1000L);
            update.setInt(2, deaths);
            update.setString(3, progress);
            update.setLong(4, expectedRevision + 1);
            update.setLong(5, now);
            update.setString(6, completionId);
            update.setString(7, realm);
            update.setString(8, server);
            update.setLong(9, expectedRevision);
            update.setString(10, ACTIVE);
            return update.executeUpdate() == 1 ? expectedRevision + 1 : -1L;
        }
    }

    /**
     * Hands a run off: the player left this server. It waits {@code handoffSeconds} for them on any
     * server of the realm.
     *
     * @return the new revision, or -1 when fenced
     */
    public long suspend(String completionId, long expectedRevision, int deaths, @Nullable String progress, long now)
            throws SQLException {
        try (PreparedStatement update = connection().prepareStatement("UPDATE " + runs
                + " SET run_state = ?, owner_server = NULL, handoff_until = ?, deaths = ?, progress = COALESCE(?, progress), "
                + "revision = ?, updated_at = ? WHERE completion_id = ? AND realm_id = ? AND owner_server = ? AND revision = ?")) {
            update.setString(1, SUSPENDED);
            update.setLong(2, now + settings.handoffSeconds() * 1000L);
            update.setInt(3, deaths);
            update.setString(4, progress);
            update.setLong(5, expectedRevision + 1);
            update.setLong(6, now);
            update.setString(7, completionId);
            update.setString(8, realm);
            update.setString(9, server);
            update.setLong(10, expectedRevision);
            return update.executeUpdate() == 1 ? expectedRevision + 1 : -1L;
        }
    }

    /** After a crash: every run this server still owns waits for its player instead (handoff). */
    public int releaseOwned(long now) throws SQLException {
        try (PreparedStatement update = connection().prepareStatement("UPDATE " + runs
                + " SET run_state = ?, owner_server = NULL, handoff_until = ?, revision = revision + 1, updated_at = ? "
                + "WHERE realm_id = ? AND owner_server = ? AND run_state = ?")) {
            update.setString(1, SUSPENDED);
            update.setLong(2, now + settings.handoffSeconds() * 1000L);
            update.setLong(3, now);
            update.setString(4, realm);
            update.setString(5, server);
            update.setString(6, ACTIVE);
            return update.executeUpdate();
        }
    }

    /** The player's unresolved runs in this realm. */
    public List<RunRow> runsOf(UUID player) throws SQLException {
        try (PreparedStatement select = connection().prepareStatement(RUN_COLUMNS + runs
                + " WHERE realm_id = ? AND player_id = ? AND run_state IN (?, ?)")) {
            select.setString(1, realm);
            select.setString(2, player.toString());
            select.setString(3, ACTIVE);
            select.setString(4, SUSPENDED);
            return readRuns(select);
        }
    }

    /**
     * Whether this server may take a run over now: it was handed off and the deadline has not passed,
     * or it is still marked active but its owner's lease ran out (the owner crashed or lost the store),
     * or this server owns it already (its own crash).
     */
    public boolean resumable(RunRow row, long now) {
        if (SUSPENDED.equals(row.state())) {
            return row.handoffUntil() >= now;
        }
        return server.equals(row.ownerServer()) || row.leaseUntil() < now;
    }

    /**
     * Takes a run over for this server.
     *
     * @return the new revision, or -1 when someone else changed it first
     */
    public long takeOver(RunRow row, long now) throws SQLException {
        try (PreparedStatement update = connection().prepareStatement("UPDATE " + runs
                + " SET run_state = ?, owner_server = ?, lease_until = ?, handoff_until = 0, revision = ?, updated_at = ? "
                + "WHERE completion_id = ? AND realm_id = ? AND revision = ?")) {
            update.setString(1, ACTIVE);
            update.setString(2, server);
            update.setLong(3, now + settings.leaseSeconds() * 1000L);
            update.setLong(4, row.revision() + 1);
            update.setLong(5, now);
            update.setString(6, row.completionId());
            update.setString(7, realm);
            update.setLong(8, row.revision());
            return update.executeUpdate() == 1 ? row.revision() + 1 : -1L;
        }
    }

    /** Runs nobody can continue any more: handed off past their deadline, or owned by a lease long gone. */
    public List<RunRow> expired(long now, int limit) throws SQLException {
        long staleLease = now - settings.handoffSeconds() * 1000L;
        try (PreparedStatement select = connection().prepareStatement(RUN_COLUMNS + runs
                + " WHERE realm_id = ? AND ((run_state = ? AND handoff_until < ?) OR (run_state = ? AND lease_until < ?))")) {
            select.setString(1, realm);
            select.setString(2, SUSPENDED);
            select.setLong(3, now);
            select.setString(4, ACTIVE);
            select.setLong(5, staleLease);
            select.setMaxRows(Math.max(1, limit));
            return readRuns(select);
        }
    }

    // ------------------------------------------------------------------ resolution

    /**
     * Resolves a run exactly once: the run row moves to its final state (compare-and-set on the
     * revision, and on the owner unless {@code asOwner} is false for a run nobody owns), the result is
     * written with its receipt, and the slot is freed, in one transaction.
     *
     * @param expectedRevision the revision the resolver last saw; the result's causal revision is one more
     * @return false when fenced: someone else changed the run first and nothing was written
     */
    public boolean resolve(String completionId, long expectedRevision, boolean asOwner, ResultRow result, long now)
            throws SQLException {
        Connection open = connection();
        boolean auto = open.getAutoCommit();
        open.setAutoCommit(false);
        try {
            int changed;
            try (PreparedStatement update = open.prepareStatement("UPDATE " + runs
                    + " SET run_state = ?, owner_server = NULL, revision = ?, updated_at = ? WHERE completion_id = ? "
                    + "AND realm_id = ? AND revision = ?" + (asOwner ? " AND owner_server = ?" : ""))) {
                update.setString(1, result.status().name());
                update.setLong(2, expectedRevision + 1);
                update.setLong(3, now);
                update.setString(4, completionId);
                update.setString(5, realm);
                update.setLong(6, expectedRevision);
                if (asOwner) {
                    update.setString(7, server);
                }
                changed = update.executeUpdate();
            }
            if (changed != 1) {
                open.rollback();
                // The result may already be there (a retry after a commit whose answer was lost).
                return result(completionId).map(existing -> existing.causalRevision() == expectedRevision + 1)
                        .orElse(false);
            }
            if (result(completionId).isEmpty()) {
                try (PreparedStatement insert = open.prepareStatement("INSERT INTO " + results
                        + " (completion_id, evidence_id, realm_id, player_id, trial_id, quest_id, quest_version, "
                        + "result_status, score, ranked, elapsed_ms, deaths, metrics, started_at, resolved_at, reason, "
                        + "causal_revision, resolved_by, receipt) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)")) {
                    insert.setString(1, completionId);
                    insert.setString(2, result.evidenceId());
                    insert.setString(3, realm);
                    insert.setString(4, result.playerId().toString());
                    insert.setString(5, result.trialId());
                    insert.setString(6, result.questId());
                    insert.setString(7, result.questVersion());
                    insert.setString(8, result.status().name());
                    insert.setDouble(9, result.score());
                    insert.setInt(10, result.ranked() ? 1 : 0);
                    insert.setLong(11, result.elapsedMillis());
                    insert.setInt(12, result.deaths());
                    insert.setString(13, result.metrics());
                    insert.setString(14, result.startedAt() == null ? null : result.startedAt().toString());
                    insert.setLong(15, result.resolvedAt().toEpochMilli());
                    insert.setString(16, result.reason());
                    insert.setLong(17, result.causalRevision());
                    insert.setString(18, result.resolvedBy());
                    insert.setString(19, result.receipt());
                    insert.executeUpdate();
                }
            }
            try (PreparedStatement free = open.prepareStatement("DELETE FROM " + slots
                    + " WHERE realm_id = ? AND completion_id = ?")) {
                free.setString(1, realm);
                free.setString(2, completionId);
                free.executeUpdate();
            }
            open.commit();
            return true;
        } catch (SQLException failure) {
            safeRollback(open);
            throw failure;
        } finally {
            open.setAutoCommit(auto);
        }
    }

    /** One result of this realm by completion id. */
    public Optional<ResultRow> result(String completionId) throws SQLException {
        try (PreparedStatement select = connection().prepareStatement(RESULT_COLUMNS + results
                + " WHERE realm_id = ? AND completion_id = ?")) {
            select.setString(1, realm);
            select.setString(2, completionId);
            List<ResultRow> found = readResults(select);
            return found.isEmpty() ? Optional.empty() : Optional.of(found.getFirst());
        }
    }

    /** The player's results in this realm, newest first, at most {@code limit}. */
    public List<ResultRow> resultsOf(UUID player, int limit) throws SQLException {
        try (PreparedStatement select = connection().prepareStatement(RESULT_COLUMNS + results
                + " WHERE realm_id = ? AND player_id = ? ORDER BY resolved_at DESC")) {
            select.setString(1, realm);
            select.setString(2, player.toString());
            select.setMaxRows(Math.max(1, limit));
            return readResults(select);
        }
    }

    // ------------------------------------------------------------------ plumbing

    private static final String RUN_COLUMNS = "SELECT completion_id, realm_id, player_id, trial_id, quest_id, quest_version, "
            + "run_state, owner_server, lease_until, handoff_until, revision, started_at, deaths, progress FROM ";

    private static final String RESULT_COLUMNS = "SELECT completion_id, evidence_id, realm_id, player_id, trial_id, quest_id, "
            + "quest_version, result_status, score, ranked, elapsed_ms, deaths, metrics, started_at, resolved_at, reason, "
            + "causal_revision, resolved_by, receipt FROM ";

    private static List<RunRow> readRuns(PreparedStatement select) throws SQLException {
        List<RunRow> found = new ArrayList<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                found.add(new RunRow(rows.getString(1), rows.getString(2), UUID.fromString(rows.getString(3)),
                        rows.getString(4), rows.getString(5), rows.getString(6), rows.getString(7), rows.getString(8),
                        rows.getLong(9), rows.getLong(10), rows.getLong(11), Instant.parse(rows.getString(12)),
                        rows.getInt(13), rows.getString(14)));
            }
        }
        return found;
    }

    private static List<ResultRow> readResults(PreparedStatement select) throws SQLException {
        List<ResultRow> found = new ArrayList<>();
        try (ResultSet rows = select.executeQuery()) {
            while (rows.next()) {
                String started = rows.getString(14);
                found.add(new ResultRow(rows.getString(1), rows.getString(2), rows.getString(3),
                        UUID.fromString(rows.getString(4)), rows.getString(5), rows.getString(6), rows.getString(7),
                        TrialStatus.valueOf(rows.getString(8)), rows.getDouble(9), rows.getInt(10) == 1, rows.getLong(11),
                        rows.getInt(12), rows.getString(13), started == null ? null : Instant.parse(started),
                        Instant.ofEpochMilli(rows.getLong(15)), rows.getString(16) == null ? "" : rows.getString(16),
                        rows.getLong(17), rows.getString(18), rows.getString(19)));
            }
        }
        return found;
    }

    /** The open connection, reopened when it broke. */
    private Connection connection() throws SQLException {
        if (connection != null) {
            try {
                if (connection.isValid(2)) {
                    return connection;
                }
            } catch (SQLException ignored) {
                // Reopen below.
            }
            closeQuietly();
        }
        Driver driver;
        try {
            driver = (Driver) Class.forName(dialect.driverClass(), true, SqlTrialStore.class.getClassLoader())
                    .getDeclaredConstructor().newInstance();
        } catch (ReflectiveOperationException | LinkageError missing) {
            throw new SQLException("the " + dialect.id() + " JDBC driver (" + dialect.driverClass()
                    + ") is not available" + (dialect == TrialDialect.H2 ? "; H2 is not bundled with MysticQuests" : ""),
                    missing);
        }
        Properties properties = new Properties();
        if (!settings.username().isBlank()) {
            properties.setProperty("user", settings.username());
        }
        String password = settings.resolvePassword();
        if (!password.isEmpty()) {
            properties.setProperty("password", password);
        }
        Connection opened = driver.connect(dialect.connectUrl(settings.jdbcUrl()), properties);
        if (opened == null) {
            throw new SQLException("the " + dialect.id() + " driver did not accept the URL " + settings.jdbcUrl());
        }
        opened.setAutoCommit(true);
        connection = opened;
        return opened;
    }

    /** Whether a failure is a unique or primary key violation (SQLSTATE class 23). */
    static boolean isDuplicate(SQLException failure) {
        for (SQLException current = failure; current != null; current = current.getNextException()) {
            String state = current.getSQLState();
            if (state != null && state.startsWith("23")) {
                return true;
            }
        }
        return false;
    }

    private static void safeRollback(Connection open) {
        try {
            open.rollback();
        } catch (SQLException ignored) {
            // The original failure is the one worth reporting.
        }
    }

    private void closeQuietly() {
        if (connection != null) {
            try {
                connection.close();
            } catch (SQLException ignored) {
                // Closing a broken connection.
            }
            connection = null;
        }
    }

    @Override
    public void close() {
        closeQuietly();
    }
}
