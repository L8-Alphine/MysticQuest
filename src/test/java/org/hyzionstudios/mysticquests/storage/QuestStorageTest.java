package org.hyzionstudios.mysticquests.storage;

import org.hyzionstudios.mysticquests.util.Json;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.time.Instant;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
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

            ScopedStateData scoped = sampleScopedState(playerId);
            storage.saveScopedState(scoped);
            ScopedStateData loadedState = storage.loadScopedState();
            assertTrue(loadedState.player().get(playerId.toString()).tags().contains("tutorial_started"));
            assertEquals("open", loadedState.global().get(ScopedStateService.GLOBAL_OWNER).variables().get("festival"));
            assertEquals("true", loadedState.entity().get("entity-1").variables().get("spoken"));
            assertTrue(loadedState.volume().get("world:village_gate").tags().contains("visited"));
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

            ScopedStateData scoped = sampleScopedState(playerId);
            storage.saveScopedState(scoped);
            ScopedStateData loadedState = storage.loadScopedState();
            assertTrue(loadedState.player().get(playerId.toString()).tags().contains("tutorial_started"));
            assertEquals("open", loadedState.global().get(ScopedStateService.GLOBAL_OWNER).variables().get("festival"));
            assertEquals("true", loadedState.entity().get("entity-1").variables().get("spoken"));
            assertTrue(loadedState.volume().get("world:village_gate").tags().contains("visited"));
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

    private ScopedStateData sampleScopedState(UUID playerId) {
        ScopedStateData data = new ScopedStateData();
        ScopedStateData.ScopedEntry player = new ScopedStateData.ScopedEntry();
        player.tags().add("tutorial_started");
        data.player().put(playerId.toString(), player);

        ScopedStateData.ScopedEntry global = new ScopedStateData.ScopedEntry();
        global.variables().put("festival", "open");
        data.global().put(ScopedStateService.GLOBAL_OWNER, global);

        ScopedStateData.ScopedEntry entity = new ScopedStateData.ScopedEntry();
        entity.variables().put("spoken", "true");
        entity.metadata().put("type", "NpcEntity");
        data.entity().put("entity-1", entity);

        ScopedStateData.ScopedEntry volume = new ScopedStateData.ScopedEntry();
        volume.tags().add("visited");
        volume.variables().put("enabled", "true");
        volume.metadata().put("world", "world");
        data.volume().put("world:village_gate", volume);
        return data;
    }
}
