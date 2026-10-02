package org.hyzionstudios.mysticquests.storage;

import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.state.StateSnapshot;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the one-time move from the version 1 {@code scoped_*} tables to {@code mq_state_*}.
 *
 * <p>This runs against a hand-built version 1 database rather than a fixture file, so the test keeps
 * describing the old schema even after the code that wrote it is gone.
 */
final class SqliteStateMigrationTest {
    @TempDir
    private Path tempDir;

    @Test
    void migratesLegacyScopedTablesAndKeepsThemForRollback() throws Exception {
        Path database = tempDir.resolve("legacy.db");
        writeVersionOneDatabase(database);

        try (QuestStorage storage = new SqliteQuestStorage(database, Json.createMapper())) {
            Map<StateKey, StateSnapshot> loaded = storage.loadState().stream()
                    .collect(Collectors.toMap(StateSnapshot::key, Function.identity()));

            assertTrue(loaded.get(StateKey.of(StateScope.PLAYER, "player-1")).tags().contains("met_elder"));
            assertEquals("open", loaded.get(StateKey.global()).variables().get("festival"));
            assertEquals("NpcEntity", loaded.get(StateKey.of(StateScope.ENTITY, "npc-7")).metadata().get("type"));
        }

        try (Connection connection = open(database)) {
            assertEquals(2, schemaVersion(connection));
            assertTrue(tableExists(connection, "scoped_tags_legacy"), "legacy rows must remain for rollback");
            assertTrue(tableExists(connection, "scoped_variables_legacy"));
            assertTrue(tableExists(connection, "scoped_metadata_legacy"));
        }
    }

    /** Reopening an already-migrated database must not run the migration again or duplicate rows. */
    @Test
    void migrationIsIdempotent() throws Exception {
        Path database = tempDir.resolve("twice.db");
        writeVersionOneDatabase(database);

        try (QuestStorage first = new SqliteQuestStorage(database, Json.createMapper())) {
            assertEquals(3, first.loadState().size());
        }
        try (QuestStorage second = new SqliteQuestStorage(database, Json.createMapper())) {
            List<StateSnapshot> loaded = second.loadState();
            assertEquals(3, loaded.size());
        }
    }

    /** A fresh database is stamped at the current version without attempting any migration. */
    @Test
    void freshDatabaseIsStampedAtCurrentVersion() throws Exception {
        Path database = tempDir.resolve("fresh.db");
        try (QuestStorage storage = new SqliteQuestStorage(database, Json.createMapper())) {
            assertTrue(storage.loadState().isEmpty());
        }
        try (Connection connection = open(database)) {
            assertEquals(2, schemaVersion(connection));
        }
    }

    private static void writeVersionOneDatabase(Path database) throws SQLException, IOException {
        java.nio.file.Files.createDirectories(database.getParent());
        try (Connection connection = open(database);
             Statement statement = connection.createStatement()) {
            statement.executeUpdate("""
                    CREATE TABLE scoped_tags (
                        scope TEXT NOT NULL, owner_id TEXT NOT NULL, tag TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, tag))
                    """);
            statement.executeUpdate("""
                    CREATE TABLE scoped_variables (
                        scope TEXT NOT NULL, owner_id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name))
                    """);
            statement.executeUpdate("""
                    CREATE TABLE scoped_metadata (
                        scope TEXT NOT NULL, owner_id TEXT NOT NULL, name TEXT NOT NULL, value TEXT NOT NULL,
                        PRIMARY KEY (scope, owner_id, name))
                    """);
            statement.executeUpdate("INSERT INTO scoped_tags VALUES ('player', 'player-1', 'met_elder')");
            statement.executeUpdate("INSERT INTO scoped_variables VALUES ('global', '__global__', 'festival', 'open')");
            statement.executeUpdate("INSERT INTO scoped_variables VALUES ('entity', 'npc-7', 'spoken', 'true')");
            statement.executeUpdate("INSERT INTO scoped_metadata VALUES ('entity', 'npc-7', 'type', 'NpcEntity')");
        }
    }

    private static Connection open(Path database) throws SQLException {
        return DriverManager.getConnection("jdbc:sqlite:" + database.toAbsolutePath());
    }

    private static int schemaVersion(Connection connection) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery("SELECT version FROM mq_schema_version LIMIT 1")) {
            return rows.next() ? rows.getInt("version") : 0;
        }
    }

    private static boolean tableExists(Connection connection, String name) throws SQLException {
        try (Statement statement = connection.createStatement();
             ResultSet rows = statement.executeQuery(
                     "SELECT 1 FROM sqlite_master WHERE type = 'table' AND name = '" + name + "'")) {
            return rows.next();
        }
    }
}
