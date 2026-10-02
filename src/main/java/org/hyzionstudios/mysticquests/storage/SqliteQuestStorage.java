package org.hyzionstudios.mysticquests.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.state.StateSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class SqliteQuestStorage implements QuestStorage {
    /** Bumped whenever the on-disk layout changes; see {@code migrateSchema}. */
    private static final int SCHEMA_VERSION = 2;

    private final Connection connection;
    private final ObjectMapper mapper;

    public SqliteQuestStorage(Path databasePath, ObjectMapper mapper) throws IOException {
        try {
            Class.forName("org.sqlite.JDBC");
            Files.createDirectories(databasePath.getParent());
            this.connection = DriverManager.getConnection("jdbc:sqlite:" + databasePath.toAbsolutePath());
            this.mapper = mapper;
            initialize();
        } catch (ClassNotFoundException | SQLException exception) {
            throw new IOException("Failed to open SQLite quest storage", exception);
        }
    }

    @Override
    public synchronized PlayerQuestData loadPlayer(UUID playerId) throws IOException {
        try {
            PlayerQuestData data = new PlayerQuestData(playerId);
            loadPlayerState(data);
            loadActiveQuests(data);
            loadCompletedQuests(data);
            loadAbandonedQuests(data);
            loadTags(data);
            loadVariables(data);
            return data;
        } catch (SQLException exception) {
            throw new IOException("Failed to load player quest data for " + playerId, exception);
        }
    }

    @Override
    public synchronized void savePlayer(PlayerQuestData data) throws IOException {
        try {
            connection.setAutoCommit(false);
            deletePlayerRows(data.playerId());
            savePlayerState(data);
            saveActiveQuests(data);
            saveCompletedQuests(data);
            saveAbandonedQuests(data);
            saveTags(data);
            saveVariables(data);
            connection.commit();
        } catch (Exception exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new IOException("Failed to save player quest data for " + data.playerId(), exception);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private void initialize() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_quests (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        started_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS objective_progress (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        objective_id TEXT NOT NULL,
                        progress INTEGER NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id, objective_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS completed_quests (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        completed_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS abandoned_quests (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT NOT NULL,
                        abandoned_at TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_tags (
                        player_uuid TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, tag)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS variables (
                        player_uuid TEXT NOT NULL,
                        quest_id TEXT,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (player_uuid, quest_id, name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS player_state (
                        player_uuid TEXT PRIMARY KEY,
                        tracked_quest_id TEXT
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mq_schema_version (
                        version INTEGER NOT NULL
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mq_state_tags (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, tag)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mq_state_variables (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS mq_state_metadata (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name)
                    )
                    """);
            // Delta writes always address a single owner, so every state query filters on
            // (scope, owner_id). Without these the writer degrades to a table scan per flush.
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mq_state_tags_owner ON mq_state_tags(scope, owner_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mq_state_variables_owner ON mq_state_variables(scope, owner_id)");
            statement.executeUpdate("CREATE INDEX IF NOT EXISTS mq_state_metadata_owner ON mq_state_metadata(scope, owner_id)");
        }
        migrateSchema();
    }

    /**
     * Brings a pre-existing database up to {@link #SCHEMA_VERSION}.
     *
     * <p>Version 1 kept scoped state in {@code scoped_tags}/{@code scoped_variables}/
     * {@code scoped_metadata}, rewritten wholesale on every mutation. Version 2 moves it to the
     * {@code mq_state_*} tables so writes can be per-owner. The legacy tables are renamed rather than
     * dropped, so a server that needs to roll back to an older jar still has its data.
     */
    private void migrateSchema() throws SQLException {
        int version = readSchemaVersion();
        if (version >= SCHEMA_VERSION) {
            return;
        }
        if (version < 2 && tableExists("scoped_tags")) {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate(
                        "INSERT OR IGNORE INTO mq_state_tags(scope, owner_id, tag) "
                                + "SELECT scope, owner_id, tag FROM scoped_tags");
                statement.executeUpdate(
                        "INSERT OR IGNORE INTO mq_state_variables(scope, owner_id, name, value) "
                                + "SELECT scope, owner_id, name, value FROM scoped_variables");
                statement.executeUpdate(
                        "INSERT OR IGNORE INTO mq_state_metadata(scope, owner_id, name, value) "
                                + "SELECT scope, owner_id, name, value FROM scoped_metadata");
                statement.executeUpdate("ALTER TABLE scoped_tags RENAME TO scoped_tags_legacy");
                statement.executeUpdate("ALTER TABLE scoped_variables RENAME TO scoped_variables_legacy");
                statement.executeUpdate("ALTER TABLE scoped_metadata RENAME TO scoped_metadata_legacy");
                connection.commit();
            } catch (SQLException exception) {
                connection.rollback();
                throw exception;
            } finally {
                connection.setAutoCommit(true);
            }
        }
        writeSchemaVersion();
    }

    private int readSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM mq_schema_version LIMIT 1")) {
            return rows.next() ? rows.getInt("version") : 0;
        }
    }

    private void writeSchemaVersion() throws SQLException {
        try (Statement statement = connection.createStatement()) {
            statement.executeUpdate("DELETE FROM mq_schema_version");
            statement.executeUpdate("INSERT INTO mq_schema_version(version) VALUES (" + SCHEMA_VERSION + ")");
        }
    }

    private boolean tableExists(String name) throws SQLException {
        try (PreparedStatement statement =
                     connection.prepareStatement("SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = ?")) {
            statement.setString(1, name);
            try (ResultSet rows = statement.executeQuery()) {
                return rows.next();
            }
        }
    }

    @Override
    public synchronized List<StateSnapshot> loadState() throws IOException {
        try {
            Map<StateKey, MutableState> accumulated = new LinkedHashMap<>();
            readState("SELECT scope, owner_id, tag FROM mq_state_tags", rows ->
                    state(accumulated, rows).tags.add(rows.getString("tag")));
            readState("SELECT scope, owner_id, name, value FROM mq_state_variables", rows ->
                    state(accumulated, rows).variables.put(rows.getString("name"), rows.getString("value")));
            readState("SELECT scope, owner_id, name, value FROM mq_state_metadata", rows ->
                    state(accumulated, rows).metadata.put(rows.getString("name"), rows.getString("value")));

            List<StateSnapshot> snapshots = new ArrayList<>(accumulated.size());
            accumulated.forEach((key, state) -> snapshots.add(
                    new StateSnapshot(key, Set.copyOf(state.tags), Map.copyOf(state.variables), Map.copyOf(state.metadata))));
            return snapshots;
        } catch (SQLException exception) {
            throw new IOException("Failed to load MysticQuests state", exception);
        }
    }

    /**
     * Rewrites only the owners handed in. Each owner's rows are deleted and reinserted inside one
     * transaction, which keeps an owner atomically consistent while leaving every other owner on the
     * server untouched.
     */
    @Override
    public synchronized void writeState(Collection<StateSnapshot> snapshots) throws IOException {
        if (snapshots.isEmpty()) {
            return;
        }
        try {
            connection.setAutoCommit(false);
            try (PreparedStatement deleteTags = connection.prepareStatement(
                         "DELETE FROM mq_state_tags WHERE scope = ? AND owner_id = ?");
                 PreparedStatement deleteVariables = connection.prepareStatement(
                         "DELETE FROM mq_state_variables WHERE scope = ? AND owner_id = ?");
                 PreparedStatement deleteMetadata = connection.prepareStatement(
                         "DELETE FROM mq_state_metadata WHERE scope = ? AND owner_id = ?");
                 PreparedStatement insertTag = connection.prepareStatement(
                         "INSERT OR REPLACE INTO mq_state_tags(scope, owner_id, tag) VALUES (?, ?, ?)");
                 PreparedStatement insertVariable = connection.prepareStatement(
                         "INSERT OR REPLACE INTO mq_state_variables(scope, owner_id, name, value) VALUES (?, ?, ?, ?)");
                 PreparedStatement insertMetadata = connection.prepareStatement(
                         "INSERT OR REPLACE INTO mq_state_metadata(scope, owner_id, name, value) VALUES (?, ?, ?, ?)")) {

                for (StateSnapshot snapshot : snapshots) {
                    String scope = snapshot.scope().id();
                    String owner = snapshot.owner();
                    bindOwner(deleteTags, scope, owner);
                    bindOwner(deleteVariables, scope, owner);
                    bindOwner(deleteMetadata, scope, owner);
                    if (snapshot.isEmpty()) {
                        continue;
                    }
                    for (String tag : snapshot.tags()) {
                        insertTag.setString(1, scope);
                        insertTag.setString(2, owner);
                        insertTag.setString(3, tag);
                        insertTag.addBatch();
                    }
                    bindNamedValues(insertVariable, scope, owner, snapshot.variables());
                    bindNamedValues(insertMetadata, scope, owner, snapshot.metadata());
                }

                deleteTags.executeBatch();
                deleteVariables.executeBatch();
                deleteMetadata.executeBatch();
                insertTag.executeBatch();
                insertVariable.executeBatch();
                insertMetadata.executeBatch();
            }
            connection.commit();
        } catch (Exception exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new IOException("Failed to save MysticQuests state", exception);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private static void bindOwner(PreparedStatement statement, String scope, String owner) throws SQLException {
        statement.setString(1, scope);
        statement.setString(2, owner);
        statement.addBatch();
    }

    private static void bindNamedValues(
            PreparedStatement statement,
            String scope,
            String owner,
            Map<String, String> values) throws SQLException {
        for (Map.Entry<String, String> value : values.entrySet()) {
            statement.setString(1, scope);
            statement.setString(2, owner);
            statement.setString(3, value.getKey());
            statement.setString(4, value.getValue());
            statement.addBatch();
        }
    }

    private void readState(String sql, StateRowReader reader) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement(sql);
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                reader.read(rows);
            }
        }
    }

    /**
     * Rows for an unknown scope are skipped rather than failing the load, so a database written by a
     * newer build that adds a scope still opens on an older jar.
     */
    private MutableState state(Map<StateKey, MutableState> accumulated, ResultSet rows) throws SQLException {
        StateScope scope = StateScope.fromId(rows.getString("scope"));
        if (scope == null) {
            return DISCARD;
        }
        return accumulated.computeIfAbsent(
                StateKey.of(scope, rows.getString("owner_id")), ignored -> new MutableState());
    }

    @FunctionalInterface
    private interface StateRowReader {
        void read(ResultSet rows) throws SQLException;
    }

    /** Sink for rows in scopes this build does not know about. */
    private static final MutableState DISCARD = new MutableState();

    private static final class MutableState {
        private final Set<String> tags = new LinkedHashSet<>();
        private final Map<String, String> variables = new LinkedHashMap<>();
        private final Map<String, String> metadata = new LinkedHashMap<>();
    }

    private void loadPlayerState(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT tracked_quest_id FROM player_state WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                if (rows.next()) {
                    data.setTrackedQuestId(rows.getString("tracked_quest_id"));
                }
            }
        }
    }

    private void loadActiveQuests(PlayerQuestData data) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quest_id, started_at FROM player_quests WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    ActiveQuestData quest = new ActiveQuestData(rows.getString("quest_id"));
                    quest.setStartedAt(Instant.parse(rows.getString("started_at")));
                    quest.setObjectiveProgress(loadObjectiveProgress(data.playerId(), quest.questId()));
                    data.activeQuests().put(quest.questId(), quest);
                }
            }
        }
    }

    private Map<String, Integer> loadObjectiveProgress(UUID playerId, String questId) throws SQLException {
        Map<String, Integer> progress = new LinkedHashMap<>();
        try (PreparedStatement statement = connection.prepareStatement("SELECT objective_id, progress FROM objective_progress WHERE player_uuid = ? AND quest_id = ?")) {
            statement.setString(1, playerId.toString());
            statement.setString(2, questId);
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    progress.put(rows.getString("objective_id"), rows.getInt("progress"));
                }
            }
        }
        return progress;
    }

    private void loadCompletedQuests(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quest_id, completed_at FROM completed_quests WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    data.completedQuests().put(rows.getString("quest_id"), Instant.parse(rows.getString("completed_at")));
                }
            }
        }
    }

    private void loadAbandonedQuests(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quest_id, abandoned_at FROM abandoned_quests WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    data.abandonedQuests().put(rows.getString("quest_id"), Instant.parse(rows.getString("abandoned_at")));
                }
            }
        }
    }

    private void loadTags(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT tag FROM player_tags WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    data.tags().add(rows.getString("tag"));
                }
            }
        }
    }

    private void loadVariables(PlayerQuestData data) throws SQLException, IOException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT quest_id, name, value FROM variables WHERE player_uuid = ?")) {
            statement.setString(1, data.playerId().toString());
            try (ResultSet rows = statement.executeQuery()) {
                while (rows.next()) {
                    String questId = rows.getString("quest_id");
                    String value = rows.getString("value");
                    if (questId == null) {
                        data.playerVariables().put(rows.getString("name"), value);
                    } else {
                        data.questVariables().computeIfAbsent(questId, ignored -> new LinkedHashMap<>()).put(rows.getString("name"), value);
                    }
                }
            }
        }
    }

    private void deletePlayerRows(UUID playerId) throws SQLException {
        for (String table : new String[] {"objective_progress", "player_quests", "completed_quests", "abandoned_quests", "player_tags", "variables", "player_state"}) {
            try (PreparedStatement statement = connection.prepareStatement("DELETE FROM " + table + " WHERE player_uuid = ?")) {
                statement.setString(1, playerId.toString());
                statement.executeUpdate();
            }
        }
    }

    private void savePlayerState(PlayerQuestData data) throws SQLException {
        if (data.trackedQuestId() == null) {
            return;
        }
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_state(player_uuid, tracked_quest_id) VALUES (?, ?)")) {
            statement.setString(1, data.playerId().toString());
            statement.setString(2, data.trackedQuestId());
            statement.executeUpdate();
        }
    }

    private void saveActiveQuests(PlayerQuestData data) throws SQLException {
        try (PreparedStatement questStatement = connection.prepareStatement("INSERT INTO player_quests(player_uuid, quest_id, started_at) VALUES (?, ?, ?)");
             PreparedStatement progressStatement = connection.prepareStatement("INSERT INTO objective_progress(player_uuid, quest_id, objective_id, progress) VALUES (?, ?, ?, ?)")) {
            for (ActiveQuestData quest : data.activeQuests().values()) {
                questStatement.setString(1, data.playerId().toString());
                questStatement.setString(2, quest.questId());
                questStatement.setString(3, quest.startedAt().toString());
                questStatement.addBatch();
                for (Map.Entry<String, Integer> progress : quest.objectiveProgress().entrySet()) {
                    progressStatement.setString(1, data.playerId().toString());
                    progressStatement.setString(2, quest.questId());
                    progressStatement.setString(3, progress.getKey());
                    progressStatement.setInt(4, progress.getValue());
                    progressStatement.addBatch();
                }
            }
            questStatement.executeBatch();
            progressStatement.executeBatch();
        }
    }

    private void saveCompletedQuests(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO completed_quests(player_uuid, quest_id, completed_at) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Instant> entry : data.completedQuests().entrySet()) {
                statement.setString(1, data.playerId().toString());
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveAbandonedQuests(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO abandoned_quests(player_uuid, quest_id, abandoned_at) VALUES (?, ?, ?)")) {
            for (Map.Entry<String, Instant> entry : data.abandonedQuests().entrySet()) {
                statement.setString(1, data.playerId().toString());
                statement.setString(2, entry.getKey());
                statement.setString(3, entry.getValue().toString());
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveTags(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO player_tags(player_uuid, tag) VALUES (?, ?)")) {
            for (String tag : data.tags()) {
                statement.setString(1, data.playerId().toString());
                statement.setString(2, tag);
                statement.addBatch();
            }
            statement.executeBatch();
        }
    }

    private void saveVariables(PlayerQuestData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("INSERT INTO variables(player_uuid, quest_id, name, value) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, String> variable : data.playerVariables().entrySet()) {
                statement.setString(1, data.playerId().toString());
                statement.setString(2, null);
                statement.setString(3, variable.getKey());
                statement.setString(4, variable.getValue());
                statement.addBatch();
            }
            for (Map.Entry<String, Map<String, String>> questVariables : data.questVariables().entrySet()) {
                for (Map.Entry<String, String> variable : questVariables.getValue().entrySet()) {
                    statement.setString(1, data.playerId().toString());
                    statement.setString(2, questVariables.getKey());
                    statement.setString(3, variable.getKey());
                    statement.setString(4, variable.getValue());
                    statement.addBatch();
                }
            }
            statement.executeBatch();
        }
    }

    @Override
    public synchronized void close() throws IOException {
        try {
            connection.close();
        } catch (SQLException exception) {
            throw new IOException("Failed to close SQLite storage", exception);
        }
    }
}
