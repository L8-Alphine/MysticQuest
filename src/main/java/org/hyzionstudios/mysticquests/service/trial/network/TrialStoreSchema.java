package org.hyzionstudios.mysticquests.service.trial.network;

import java.sql.Connection;
import java.sql.DatabaseMetaData;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * The versioned schema of the shared trial store (R-1) with its migrations, rollback statements and a
 * dry run. Documented in docs/integrations/network-trials.md ("Schema and migrations").
 *
 * <p>Version 1 creates five tables, all named {@code <prefix>trial_*}:
 * <ul>
 *   <li>{@code trial_schema}: the applied version (one row, component {@code trials}).</li>
 *   <li>{@code trial_servers}: every server of a realm with its heartbeat; a transfer target must be a
 *       fresh member.</li>
 *   <li>{@code trial_runs}: runs in progress or handed off, with the owning server, its lease, the
 *       handoff deadline, the revision (fencing token and causal revision) and the progress
 *       snapshot.</li>
 *   <li>{@code trial_slots}: at most one run per realm, player and trial.</li>
 *   <li>{@code trial_results}: resolved runs with their completion receipts (the evidence).</li>
 * </ul>
 *
 * <p>Applying is idempotent: tables use {@code CREATE TABLE IF NOT EXISTS} and an index that already
 * exists is accepted, so two servers starting at once both succeed.
 */
public final class TrialStoreSchema {

    /** The component name in {@code trial_schema}. */
    public static final String COMPONENT = "trials";

    /** One migration step. */
    public record Migration(int version, String description, List<String> up, List<String> down) {
    }

    private final TrialDialect dialect;
    private final String prefix;

    public TrialStoreSchema(TrialDialect dialect, String prefix) {
        this.dialect = dialect;
        this.prefix = prefix;
    }

    public String table(String name) {
        return prefix + name;
    }

    /** Every migration in order. */
    public List<Migration> migrations() {
        String text = dialect.textType();
        String p = prefix;
        List<String> up = List.of(
                "CREATE TABLE IF NOT EXISTS " + p + "trial_schema (component VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "version INT NOT NULL, applied_at BIGINT NOT NULL)",
                "CREATE TABLE IF NOT EXISTS " + p + "trial_servers (realm_id VARCHAR(64) NOT NULL, "
                        + "server_id VARCHAR(64) NOT NULL, contract_version INT NOT NULL, schema_version INT NOT NULL, "
                        + "started_at BIGINT NOT NULL, heartbeat_at BIGINT NOT NULL, PRIMARY KEY (realm_id, server_id))",
                "CREATE TABLE IF NOT EXISTS " + p + "trial_runs (completion_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "realm_id VARCHAR(64) NOT NULL, player_id VARCHAR(36) NOT NULL, trial_id VARCHAR(128) NOT NULL, "
                        + "quest_id VARCHAR(160) NOT NULL, quest_version VARCHAR(64) NOT NULL, "
                        + "run_state VARCHAR(16) NOT NULL, owner_server VARCHAR(64), lease_until BIGINT NOT NULL, "
                        + "handoff_until BIGINT NOT NULL, revision BIGINT NOT NULL, started_at VARCHAR(40) NOT NULL, "
                        + "deaths INT NOT NULL, progress " + text + ", updated_at BIGINT NOT NULL)",
                "CREATE INDEX " + p + "trial_runs_player ON " + p + "trial_runs (realm_id, player_id)",
                "CREATE INDEX " + p + "trial_runs_state ON " + p + "trial_runs (realm_id, run_state)",
                "CREATE TABLE IF NOT EXISTS " + p + "trial_slots (realm_id VARCHAR(64) NOT NULL, "
                        + "player_id VARCHAR(36) NOT NULL, trial_id VARCHAR(128) NOT NULL, "
                        + "completion_id VARCHAR(64) NOT NULL, PRIMARY KEY (realm_id, player_id, trial_id))",
                "CREATE TABLE IF NOT EXISTS " + p + "trial_results (completion_id VARCHAR(64) NOT NULL PRIMARY KEY, "
                        + "evidence_id VARCHAR(36) NOT NULL, realm_id VARCHAR(64) NOT NULL, player_id VARCHAR(36) NOT NULL, "
                        + "trial_id VARCHAR(128) NOT NULL, quest_id VARCHAR(160) NOT NULL, quest_version VARCHAR(64) NOT NULL, "
                        + "result_status VARCHAR(16) NOT NULL, score DOUBLE PRECISION NOT NULL, ranked INT NOT NULL, "
                        + "elapsed_ms BIGINT NOT NULL, deaths INT NOT NULL, metrics " + text + ", "
                        + "started_at VARCHAR(40), resolved_at BIGINT NOT NULL, reason " + text + ", "
                        + "causal_revision BIGINT NOT NULL, resolved_by VARCHAR(64) NOT NULL, receipt VARCHAR(64) NOT NULL)",
                "CREATE UNIQUE INDEX " + p + "trial_results_evidence ON " + p + "trial_results (evidence_id)",
                "CREATE INDEX " + p + "trial_results_player ON " + p + "trial_results (realm_id, player_id, trial_id)");
        List<String> down = List.of(
                "DROP TABLE IF EXISTS " + p + "trial_results",
                "DROP TABLE IF EXISTS " + p + "trial_slots",
                "DROP TABLE IF EXISTS " + p + "trial_runs",
                "DROP TABLE IF EXISTS " + p + "trial_servers",
                "DROP TABLE IF EXISTS " + p + "trial_schema");
        return List.of(new Migration(1, "shared trial store: servers, runs, slots, results", up, down));
    }

    /** The newest version this build knows. */
    public int latestVersion() {
        return migrations().getLast().version();
    }

    /** The applied version; 0 when the store has never been migrated. */
    public int currentVersion(Connection connection) throws SQLException {
        if (!tableExists(connection, prefix + "trial_schema")) {
            return 0;
        }
        try (PreparedStatement select = connection.prepareStatement(
                "SELECT version FROM " + prefix + "trial_schema WHERE component = ?")) {
            select.setString(1, COMPONENT);
            try (ResultSet rows = select.executeQuery()) {
                return rows.next() ? rows.getInt(1) : 0;
            }
        }
    }

    /** The statements {@link #apply} would run from {@code fromVersion}; nothing is executed. */
    public List<String> dryRun(int fromVersion) {
        List<String> statements = new ArrayList<>();
        for (Migration migration : migrations()) {
            if (migration.version() > fromVersion) {
                statements.add("-- migration " + migration.version() + ": " + migration.description());
                statements.addAll(migration.up());
                statements.add("-- then record version " + migration.version() + " in " + prefix + "trial_schema");
            }
        }
        return statements;
    }

    /** The rollback of every applied migration, newest first; nothing is executed. */
    public List<String> rollback(int fromVersion) {
        List<String> statements = new ArrayList<>();
        List<Migration> all = new ArrayList<>(migrations());
        java.util.Collections.reverse(all);
        for (Migration migration : all) {
            if (migration.version() <= fromVersion) {
                statements.add("-- rollback of migration " + migration.version() + ": " + migration.description());
                statements.addAll(migration.down());
            }
        }
        return statements;
    }

    /**
     * Applies every migration newer than the store's version.
     *
     * @return the version now applied
     * @throws SQLException when a statement fails; the store is then left at the last whole version
     */
    public int apply(Connection connection) throws SQLException {
        int current = currentVersion(connection);
        if (current > latestVersion()) {
            throw new SQLException("the trial store is at schema version " + current + ", newer than this "
                    + "MysticQuests understands (" + latestVersion() + "); update MysticQuests on this server");
        }
        for (Migration migration : migrations()) {
            if (migration.version() <= current) {
                continue;
            }
            for (String statement : migration.up()) {
                execute(connection, statement);
            }
            record(connection, migration.version());
            current = migration.version();
        }
        return current;
    }

    private void execute(Connection connection, String sql) throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.execute(sql);
        } catch (SQLException failure) {
            // CREATE INDEX has no portable IF NOT EXISTS (MySQL lacks it): an index that is already
            // there, from another server starting at the same time, is fine.
            if (sql.startsWith("CREATE INDEX") || sql.startsWith("CREATE UNIQUE INDEX")) {
                String name = sql.split("\\s+")[sql.startsWith("CREATE UNIQUE") ? 3 : 2];
                String table = sql.substring(sql.indexOf(" ON ") + 4, sql.indexOf(" (")).trim();
                if (indexExists(connection, table, name)) {
                    return;
                }
            }
            throw failure;
        }
    }

    private void record(Connection connection, int version) throws SQLException {
        try (PreparedStatement update = connection.prepareStatement(
                "UPDATE " + prefix + "trial_schema SET version = ?, applied_at = ? WHERE component = ?")) {
            update.setInt(1, version);
            update.setLong(2, System.currentTimeMillis());
            update.setString(3, COMPONENT);
            if (update.executeUpdate() > 0) {
                return;
            }
        }
        try (PreparedStatement insert = connection.prepareStatement(
                "INSERT INTO " + prefix + "trial_schema (component, version, applied_at) VALUES (?, ?, ?)")) {
            insert.setString(1, COMPONENT);
            insert.setInt(2, version);
            insert.setLong(3, System.currentTimeMillis());
            insert.executeUpdate();
        } catch (SQLException raced) {
            if (!SqlTrialStore.isDuplicate(raced)) {
                throw raced;
            }
        }
    }

    static boolean tableExists(Connection connection, String table) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String candidate : new String[] {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = meta.getTables(connection.getCatalog(), null, candidate, null)) {
                if (rows.next()) {
                    return true;
                }
            }
        }
        return false;
    }

    private static boolean indexExists(Connection connection, String table, String index) throws SQLException {
        DatabaseMetaData meta = connection.getMetaData();
        for (String candidate : new String[] {table, table.toUpperCase(Locale.ROOT), table.toLowerCase(Locale.ROOT)}) {
            try (ResultSet rows = meta.getIndexInfo(connection.getCatalog(), null, candidate, false, true)) {
                while (rows.next()) {
                    String name = rows.getString("INDEX_NAME");
                    if (name != null && name.equalsIgnoreCase(index)) {
                        return true;
                    }
                }
            }
        }
        return false;
    }
}
