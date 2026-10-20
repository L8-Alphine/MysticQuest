package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StoryObjectivesTest {
    private static final ObjectMapper MAPPER = Json.createMapper();
    private static final UUID PLAYER = UUID.randomUUID();

    @Test
    void aWorldEventObjectiveWaitsForThePhaseItNames() {
        ObjectiveDefinition spotted = objective("""
                { "id": "spot", "type": "worldEvent", "event": "Goblin_Breach_First_Event" }""");
        ObjectiveDefinition cleared = objective("""
                { "id": "clear", "type": "worldEvent", "phase": "cleared",
                  "targets": ["Goblin_Breach_First_Event", "MysticQuests_Goblin_Breach_Portal"] }""");

        assertTrue(StoryObjectives.matches(spotted, signal("worldEventDetected", "Goblin_Breach_First_Event")));
        assertFalse(StoryObjectives.matches(spotted, signal("worldEventCompleted", "Goblin_Breach_First_Event")));
        assertTrue(StoryObjectives.matches(cleared, signal("worldEventCompleted", "MysticQuests_Goblin_Breach_Portal")));
        assertFalse(StoryObjectives.matches(cleared, signal("worldEventCompleted", "Goblin_Breach_Perpetual_Event")));
    }

    @Test
    void anUnknownPhaseMatchesNothing() {
        ObjectiveDefinition typo = objective("""
                { "id": "x", "type": "worldEvent", "event": "Hunt_Breach", "phase": "openned" }""");

        assertEquals("", StoryObjectives.signalFor(typo));
        assertFalse(StoryObjectives.matches(typo, signal("worldEventDetected", "Hunt_Breach")));
    }

    @Test
    void bossesSignalsAndEncountersMatchByTheirOwnKeyOrAnyTarget() {
        assertTrue(StoryObjectives.matches(
                objective("{ \"id\": \"b\", \"type\": \"bossDefeated\", \"boss\": [\"Goblin_Gobliterator_Phase3\", \"Goblin_Gobliterator_Phase3_HM\"] }"),
                signal("bossDefeated", "Goblin_Gobliterator_Phase3_HM")));
        assertFalse(StoryObjectives.matches(
                objective("{ \"id\": \"b\", \"type\": \"bossDefeated\", \"boss\": \"Goblin_Gobliterator_Phase3\" }"),
                signal("bossDefeated", "Goblin_Gobliterator_Explosive_Barrel")));
        assertTrue(StoryObjectives.matches(
                objective("{ \"id\": \"s\", \"type\": \"worldEventSignal\", \"signal\": \"goblin_initial_breach_cleared\" }"),
                signal("worldEventSignal", "goblin_initial_breach_cleared")));
        assertTrue(StoryObjectives.matches(
                objective("{ \"id\": \"e\", \"type\": \"encounter\", \"target\": \"*\" }"),
                signal("encounterJoined", "Goblin_Gobliterator_Encounter")));
    }

    @Test
    void anInstanceAnswersToItsAssetAndToItsWorld() {
        ObjectiveDefinition byAsset = objective("{ \"id\": \"i\", \"type\": \"enterInstance\", \"instance\": \"Portals/Portals_Goblins\" }");
        ObjectiveDefinition byWorld = objective("{ \"id\": \"w\", \"type\": \"enterInstance\", \"world\": \"hub\" }");
        QuestSignal entered = new QuestSignal(PLAYER, "enterInstance", "Portals/Portals_Goblins", 1, null, 0, 0, 0,
                new QuestTargetContext(null, null, null, null, null, "instance-portals_goblins-1", null, null, null));
        QuestSignal hub = new QuestSignal(PLAYER, "enterInstance", "hub", 1, null, 0, 0, 0,
                new QuestTargetContext(null, null, null, null, null, "hub", null, null, null));

        assertTrue(StoryObjectives.matches(byAsset, entered));
        assertTrue(StoryObjectives.matches(byWorld, hub));
        assertFalse(StoryObjectives.matches(byWorld, entered));
    }

    @Test
    void gameFlagsComparePolledLevels() {
        Map<String, Integer> flags = Map.of("goblin_lab_power", 1, "goblin_breach_duration", 2);
        ObjectiveDefinition powered = objective("{ \"id\": \"p\", \"type\": \"gameFlag\", \"flag\": \"goblin_lab_power\" }");
        ObjectiveDefinition upgraded = objective(
                "{ \"id\": \"u\", \"type\": \"gameFlag\", \"flag\": \"goblin_breach_duration\", \"level\": 3 }");
        ObjectiveDefinition untouched = objective(
                "{ \"id\": \"n\", \"type\": \"gameFlag\", \"flag\": \"goblin_lab_gate\", \"level\": 0, \"comparator\": \"LessOrEqual\" }");

        assertTrue(StoryObjectives.flagPasses(powered, flag -> flags.getOrDefault(flag, 0)));
        assertFalse(StoryObjectives.flagPasses(upgraded, flag -> flags.getOrDefault(flag, 0)));
        assertTrue(StoryObjectives.flagPasses(untouched, flag -> flags.getOrDefault(flag, 0)));
        assertFalse(StoryObjectives.matches(powered, signal("gameFlag", "goblin_lab_power")), "flags are polled, never signalled");
    }

    private static ObjectiveDefinition objective(String json) {
        try {
            return MAPPER.readValue(json, ObjectiveDefinition.class);
        } catch (Exception malformed) {
            throw new IllegalArgumentException(malformed);
        }
    }

    private static QuestSignal signal(String type, String target) {
        return QuestSignal.targeted(PLAYER, type, target, 1, QuestTargetContext.none());
    }
}
