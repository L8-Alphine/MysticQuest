package org.hyzionstudios.mysticquests.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public final class SqliteQuestStorage implements QuestStorage {
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
                    CREATE TABLE IF NOT EXISTS scoped_tags (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        tag TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, tag)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS scoped_variables (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name)
                    )
                    """);
            statement.executeUpdate("""
                    CREATE TABLE IF NOT EXISTS scoped_metadata (
                        scope TEXT NOT NULL,
                        owner_id TEXT NOT NULL,
                        name TEXT NOT NULL,
                        value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name)
                    )
                    """);
        }
    }

    @Override
    public synchronized ScopedStateData loadScopedState() throws IOException {
        try {
            ScopedStateData data = new ScopedStateData();
            loadScopedTags(data);
            loadScopedVariables(data);
            loadScopedMetadata(data);
            return data;
        } catch (SQLException exception) {
            throw new IOException("Failed to load MysticQuests scoped state", exception);
        }
    }

    @Override
    public synchronized void saveScopedState(ScopedStateData data) throws IOException {
        try {
            connection.setAutoCommit(false);
            try (Statement statement = connection.createStatement()) {
                statement.executeUpdate("DELETE FROM scoped_tags");
                statement.executeUpdate("DELETE FROM scoped_variables");
                statement.executeUpdate("DELETE FROM scoped_metadata");
            }
            saveScopedEntries("player", data.player());
            saveScopedEntries("global", data.global());
            saveScopedEntries("entity", data.entity());
            saveScopedEntries("block", data.block());
            saveScopedEntries("volume", data.volume());
            connection.commit();
        } catch (Exception exception) {
            try {
                connection.rollback();
            } catch (SQLException ignored) {
            }
            throw new IOException("Failed to save MysticQuests scoped state", exception);
        } finally {
            try {
                connection.setAutoCommit(true);
            } catch (SQLException ignored) {
            }
        }
    }

    private void loadScopedTags(ScopedStateData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT scope, owner_id, tag FROM scoped_tags");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                scopedEntry(data, rows.getString("scope"), rows.getString("owner_id")).tags().add(rows.getString("tag"));
            }
        }
    }

    private void loadScopedVariables(ScopedStateData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT scope, owner_id, name, value FROM scoped_variables");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                scopedEntry(data, rows.getString("scope"), rows.getString("owner_id"))
                        .variables()
                        .put(rows.getString("name"), rows.getString("value"));
            }
        }
    }

    private void loadScopedMetadata(ScopedStateData data) throws SQLException {
        try (PreparedStatement statement = connection.prepareStatement("SELECT scope, owner_id, name, value FROM scoped_metadata");
             ResultSet rows = statement.executeQuery()) {
            while (rows.next()) {
                scopedEntry(data, rows.getString("scope"), rows.getString("owner_id"))
                        .metadata()
                        .put(rows.getString("name"), rows.getString("value"));
            }
        }
    }

    private ScopedStateData.ScopedEntry scopedEntry(ScopedStateData data, String scope, String ownerId) {
        return data.entries(scope).computeIfAbsent(ownerId, ignored -> new ScopedStateData.ScopedEntry());
    }

    private void saveScopedEntries(String scope, Map<String, ScopedStateData.ScopedEntry> entries) throws SQLException {
        try (PreparedStatement tagStatement = connection.prepareStatement("INSERT INTO scoped_tags(scope, owner_id, tag) VALUES (?, ?, ?)");
             PreparedStatement variableStatement = connection.prepareStatement("INSERT INTO scoped_variables(scope, owner_id, name, value) VALUES (?, ?, ?, ?)");
             PreparedStatement metadataStatement = connection.prepareStatement("INSERT INTO scoped_metadata(scope, owner_id, name, value) VALUES (?, ?, ?, ?)")) {
            for (Map.Entry<String, ScopedStateData.ScopedEntry> entry : entries.entrySet()) {
                for (String tag : entry.getValue().tags()) {
                    tagStatement.setString(1, scope);
                    tagStatement.setString(2, entry.getKey());
                    tagStatement.setString(3, tag);
                    tagStatement.addBatch();
                }
                for (Map.Entry<String, String> variable : entry.getValue().variables().entrySet()) {
                    variableStatement.setString(1, scope);
                    variableStatement.setString(2, entry.getKey());
                    variableStatement.setString(3, variable.getKey());
                    variableStatement.setString(4, variable.getValue());
                    variableStatement.addBatch();
                }
                for (Map.Entry<String, String> metadata : entry.getValue().metadata().entrySet()) {
                    metadataStatement.setString(1, scope);
                    metadataStatement.setString(2, entry.getKey());
                    metadataStatement.setString(3, metadata.getKey());
                    metadataStatement.setString(4, metadata.getValue());
                    metadataStatement.addBatch();
                }
            }
            tagStatement.executeBatch();
            variableStatement.executeBatch();
            metadataStatement.executeBatch();
        }
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
        for (String table : new String[] {"objective_progress", "player_quests", "completed_quests", "player_tags", "variables", "player_state"}) {
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
