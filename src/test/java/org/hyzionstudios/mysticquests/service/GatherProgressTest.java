package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;

import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Gather objectives follow what the player holds, like Hytale's own gather tasks. */
final class GatherProgressTest {
    private static ObjectiveDefinition objective(String id, String type, String target, int amount) {
        ObjectiveDefinition objective = new ObjectiveDefinition();
        objective.setId(id);
        objective.setType(type);
        objective.put("target", TextNode.valueOf(target));
        objective.put("amount", IntNode.valueOf(amount));
        return objective;
    }

    private static QuestDefinition quest() {
        QuestDefinition quest = new QuestDefinition();
        quest.setId("herbs");
        quest.setObjectives(List.of(
                objective("flowers", "gather", "Plant_Flower_Blue", 5),
                objective("talk", "dialogue", "greenvale:farmer", 1)));
        return quest;
    }

    @Test
    void progressIsTheHeldCountCappedAtTheTarget() {
        QuestDefinition quest = quest();
        ActiveQuestData active = new ActiveQuestData("greenvale:herbs");
        Map<String, Integer> held = new java.util.HashMap<>(Map.of("Plant_Flower_Blue", 3));

        assertTrue(GatherProgress.sync(quest, active, item -> held.getOrDefault(item, 0)));
        assertEquals(3, active.objectiveProgress().get("flowers"));
        assertFalse(active.objectiveProgress().containsKey("talk"), "other objective types are left alone");

        assertFalse(GatherProgress.sync(quest, active, item -> held.getOrDefault(item, 0)), "no change, no save");

        held.put("Plant_Flower_Blue", 12);
        GatherProgress.sync(quest, active, item -> held.getOrDefault(item, 0));
        assertEquals(5, active.objectiveProgress().get("flowers"), "capped at the amount");

        held.put("Plant_Flower_Blue", 1);
        GatherProgress.sync(quest, active, item -> held.getOrDefault(item, 0));
        assertEquals(1, active.objectiveProgress().get("flowers"), "dropping items lowers progress, as in vanilla");
    }

    @Test
    void onlyQuestsWithAGatherTargetNeedTheInventoryHook() {
        assertTrue(GatherProgress.has(quest()));
        QuestDefinition noTarget = new QuestDefinition();
        noTarget.setObjectives(List.of(objective("x", "gather", " ", 1), objective("y", "kill", "Wolf_Black", 1)));
        assertFalse(GatherProgress.has(noTarget));
    }
}
