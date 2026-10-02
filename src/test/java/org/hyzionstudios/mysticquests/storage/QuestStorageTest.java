package org.hyzionstudios.mysticquests.storage;

import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.state.StateSnapshot;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Function;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestStorageTest {
    @TempDir
    private java.nio.file.Path tempDir;

    @Test
    void jsonStorageRoundTripsPlayerProgress() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (QuestStorage storage = new JsonQuestStorage(tempDir.resolve("players"), Json.createMapper())) {
            PlayerQuestData data = sampleData(playerId);
            storage.savePlayer(data);

            PlayerQuestData loaded = storage.loadPlayer(playerId);

            assertEquals(2, loaded.activeQuests().get("tutorial:starter_hunt").objectiveProgress().get("kill_boars"));
            assertTrue(loaded.completedQuests().containsKey("tutorial:intro"));
            assertTrue(loaded.tags().contains("tutorial_started"));
            assertEquals("3", loaded.playerVariables().get("dragon_kills"));
            assertEquals("tutorial:starter_hunt", loaded.trackedQuestId());
            assertEquals(Instant.parse("2026-06-19T09:30:00Z"), loaded.abandonedQuests().get("tutorial:side_trek"));

            storage.writeState(sampleState(playerId));
            Map<StateKey, StateSnapshot> loadedState = byKey(storage.loadState());
            assertTrue(loadedState.get(StateKey.of(StateScope.PLAYER, playerId.toString())).tags().contains("tutorial_started"));
            assertEquals("open", loadedState.get(StateKey.global()).variables().get("festival"));
            assertEquals("true", loadedState.get(StateKey.of(StateScope.ENTITY, "entity-1")).variables().get("spoken"));
            assertEquals("NpcEntity", loadedState.get(StateKey.of(StateScope.ENTITY, "entity-1")).metadata().get("type"));
            assertTrue(loadedState.get(StateKey.of(StateScope.VOLUME, "world:village_gate")).tags().contains("visited"));
        }
    }

    @Test
    void sqliteStorageRoundTripsPlayerProgress() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (QuestStorage storage = new SqliteQuestStorage(tempDir.resolve("mysticquests.db"), Json.createMapper())) {
            PlayerQuestData data = sampleData(playerId);
            storage.savePlayer(data);

            PlayerQuestData loaded = storage.loadPlayer(playerId);

            assertEquals(2, loaded.activeQuests().get("tutorial:starter_hunt").objectiveProgress().get("kill_boars"));
            assertTrue(loaded.completedQuests().containsKey("tutorial:intro"));
            assertTrue(loaded.tags().contains("tutorial_started"));
            assertEquals("3", loaded.playerVariables().get("dragon_kills"));
            assertEquals("tutorial:starter_hunt", loaded.trackedQuestId());
            assertEquals(Instant.parse("2026-06-19T09:30:00Z"), loaded.abandonedQuests().get("tutorial:side_trek"));

            storage.writeState(sampleState(playerId));
            Map<StateKey, StateSnapshot> loadedState = byKey(storage.loadState());
            assertTrue(loadedState.get(StateKey.of(StateScope.PLAYER, playerId.toString())).tags().contains("tutorial_started"));
            assertEquals("open", loadedState.get(StateKey.global()).variables().get("festival"));
            assertEquals("true", loadedState.get(StateKey.of(StateScope.ENTITY, "entity-1")).variables().get("spoken"));
            assertEquals("NpcEntity", loadedState.get(StateKey.of(StateScope.ENTITY, "entity-1")).metadata().get("type"));
            assertTrue(loadedState.get(StateKey.of(StateScope.VOLUME, "world:village_gate")).tags().contains("visited"));
        }
    }

    private PlayerQuestData sampleData(UUID playerId) {
        PlayerQuestData data = new PlayerQuestData(playerId);
        ActiveQuestData active = new ActiveQuestData("tutorial:starter_hunt");
        active.objectiveProgress().put("kill_boars", 2);
        data.activeQuests().put(active.questId(), active);
        data.setTrackedQuestId(active.questId());
        data.completedQuests().put("tutorial:intro", Instant.parse("2026-06-18T12:00:00Z"));
        data.abandonedQuests().put("tutorial:side_trek", Instant.parse("2026-06-19T09:30:00Z"));
        data.tags().add("tutorial_started");
        data.playerVariables().put("dragon_kills", "3");
        data.questVariables().computeIfAbsent("tutorial:starter_hunt", ignored -> new java.util.LinkedHashMap<>()).put("stage", "hunt");
        return data;
    }

    /**
     * A delta write must leave owners it was not given alone. The previous implementation rewrote
     * every scoped row on each save, so this is the behaviour change worth pinning down.
     */
    @Test
    void deltaWriteTouchesOnlyTheOwnersGiven() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (QuestStorage storage = new SqliteQuestStorage(tempDir.resolve("delta.db"), Json.createMapper())) {
            storage.writeState(sampleState(playerId));

            StateKey global = StateKey.global();
            storage.writeState(List.of(new StateSnapshot(
                    global, Set.of("festival_running"), Map.of("festival", "closed"), Map.of())));

            Map<StateKey, StateSnapshot> loaded = byKey(storage.loadState());
            assertEquals("closed", loaded.get(global).variables().get("festival"));
            assertTrue(loaded.get(global).tags().contains("festival_running"));
            // Untouched owners survive intact.
            assertTrue(loaded.get(StateKey.of(StateScope.ENTITY, "entity-1")).variables().containsKey("spoken"));
            assertTrue(loaded.get(StateKey.of(StateScope.VOLUME, "world:village_gate")).tags().contains("visited"));
        }
    }

    /** An empty snapshot is the "this owner has no state" signal and must delete its rows. */
    @Test
    void emptySnapshotDeletesTheOwner() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (QuestStorage storage = new SqliteQuestStorage(tempDir.resolve("clear.db"), Json.createMapper())) {
            storage.writeState(sampleState(playerId));
            StateKey entity = StateKey.of(StateScope.ENTITY, "entity-1");

            storage.writeState(List.of(new StateSnapshot(entity, Set.of(), Map.of(), Map.of())));

            Map<StateKey, StateSnapshot> loaded = byKey(storage.loadState());
            assertNull(loaded.get(entity));
            assertFalse(loaded.isEmpty(), "other owners should be untouched");
        }
    }

    /** JSON storage rewrites only the scope files whose owners changed. */
    @Test
    void jsonDeltaWriteMergesIntoExistingScopeFiles() throws IOException {
        UUID playerId = UUID.randomUUID();
        try (QuestStorage storage = new JsonQuestStorage(tempDir.resolve("json-delta"), Json.createMapper())) {
            storage.writeState(sampleState(playerId));
            StateKey volume = StateKey.of(StateScope.VOLUME, "world:market");
            storage.writeState(List.of(new StateSnapshot(volume, Set.of("open"), Map.of(), Map.of())));

            Map<StateKey, StateSnapshot> loaded = byKey(storage.loadState());
            assertTrue(loaded.get(volume).tags().contains("open"));
            assertTrue(loaded.get(StateKey.of(StateScope.VOLUME, "world:village_gate")).tags().contains("visited"));
            assertEquals("open", loaded.get(StateKey.global()).variables().get("festival"));
        }
    }

    private static Map<StateKey, StateSnapshot> byKey(List<StateSnapshot> snapshots) {
        return snapshots.stream().collect(Collectors.toMap(StateSnapshot::key, Function.identity()));
    }

    private List<StateSnapshot> sampleState(UUID playerId) {
        return List.of(
                new StateSnapshot(
                        StateKey.of(StateScope.PLAYER, playerId.toString()),
                        Set.of("tutorial_started"), Map.of(), Map.of()),
                new StateSnapshot(
                        StateKey.global(),
                        Set.of(), Map.of("festival", "open"), Map.of()),
                new StateSnapshot(
                        StateKey.of(StateScope.ENTITY, "entity-1"),
                        Set.of(), Map.of("spoken", "true"), Map.of("type", "NpcEntity")),
                new StateSnapshot(
                        StateKey.of(StateScope.VOLUME, "world:village_gate"),
                        Set.of("visited"), Map.of("enabled", "true"), Map.of("world", "world")));
    }
}
