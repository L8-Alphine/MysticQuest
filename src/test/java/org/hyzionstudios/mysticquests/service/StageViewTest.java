package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.StageDefinition;

import com.fasterxml.jackson.databind.node.TextNode;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StageViewTest {
    /** Every objective complete unless its id starts with "todo". */
    private static final java.util.function.Function<ObjectiveDefinition, ObjectiveView> VIEWER =
            objective -> ObjectiveView.of(
                    objective.id(),
                    objective.displayName(),
                    objective.id().startsWith("todo") ? 0 : 1,
                    1);

    @Test
    void ungroupedObjectivesBecomeOneUnnamedStep() {
        List<StageView> stages = StageView.group(
                List.of(), List.of(objective("todo_a", null), objective("todo_b", null)), VIEWER);

        assertEquals(1, stages.size());
        assertFalse(stages.getFirst().named());
        assertEquals(2, stages.getFirst().objectives().size());
        assertFalse(JournalEntry.ungrouped("q", "Q", "", List.of(), false).grouped());
    }

    @Test
    void objectivesInheritTheStageOfTheObjectiveAboveThem() {
        List<StageView> stages = StageView.group(
                List.of(),
                List.of(
                        objective("todo_a", "discover"),
                        objective("todo_b", null),
                        objective("todo_c", "keepers")),
                VIEWER);

        assertEquals(List.of("discover", "keepers"), stages.stream().map(StageView::stageId).toList());
        assertEquals(2, stages.getFirst().objectives().size());
        assertEquals(1, stages.get(1).objectives().size());
    }

    @Test
    void objectivesBeforeAnyStageFormOneLeadingGroup() {
        List<StageView> stages = StageView.group(
                List.of(), List.of(objective("todo_a", null), objective("todo_b", "keepers")), VIEWER);

        assertEquals(2, stages.size());
        assertFalse(stages.getFirst().named());
        assertEquals("Objectives", stages.getFirst().displayName());
    }

    @Test
    void declaredStagesSetTheOrderAndTheNames() {
        List<StageView> stages = StageView.group(
                List.of(stage("keepers", "Seek the Keepers"), stage("discover", "Discover Hyzion")),
                List.of(objective("todo_a", "discover"), objective("todo_b", "keepers")),
                VIEWER);

        assertEquals(List.of("keepers", "discover"), stages.stream().map(StageView::stageId).toList());
        assertEquals("Seek the Keepers", stages.getFirst().displayName());
        assertEquals("STEP 1 OF 2", stages.getFirst().stepLabel());
        assertEquals("STEP 2 OF 2", stages.get(1).stepLabel());
    }

    @Test
    void undeclaredStagesFollowInFirstAppearanceOrderWithDerivedNames() {
        List<StageView> stages = StageView.group(
                List.of(stage("keepers", "Seek the Keepers")),
                List.of(objective("todo_a", "citadel_keepers"), objective("todo_b", "keepers")),
                VIEWER);

        assertEquals(List.of("keepers", "citadel_keepers"), stages.stream().map(StageView::stageId).toList());
        assertEquals("Citadel Keepers", stages.get(1).displayName());
    }

    @Test
    void instructionAuthoredStagesAreReadFromTheCatchAllMap() {
        ObjectiveDefinition objective = new ObjectiveDefinition();
        objective.setId("todo_a");
        objective.put("stage", TextNode.valueOf("hunt"));

        assertEquals("hunt", objective.stage());
        assertEquals("hunt", StageView.group(List.of(), List.of(objective), VIEWER).getFirst().stageId());
    }

    @Test
    void currentStageIsTheFirstWithWorkLeft() {
        JournalEntry entry = entryOf(
                objective("done_a", "discover"),
                objective("todo_b", "keepers"),
                objective("todo_c", "avalon"));

        assertTrue(entry.grouped());
        assertEquals("keepers", entry.currentStage().stageId());
        assertEquals("1 / 3", entry.progressLabel());
    }

    @Test
    void currentStageIsTheLastOnceEverythingIsDone() {
        JournalEntry entry = entryOf(objective("done_a", "discover"), objective("done_b", "avalon"));

        assertEquals("avalon", entry.currentStage().stageId());
        assertEquals(1.0f, entry.progressRatio());
    }

    private JournalEntry entryOf(ObjectiveDefinition... objectives) {
        List<ObjectiveDefinition> definitions = List.of(objectives);
        return new JournalEntry(
                "pack:quest",
                "Quest",
                "",
                definitions.stream().map(VIEWER).toList(),
                StageView.group(List.of(), definitions, VIEWER),
                false);
    }

    private ObjectiveDefinition objective(String id, String stage) {
        ObjectiveDefinition objective = new ObjectiveDefinition();
        objective.setId(id);
        objective.setType("custom");
        objective.setStage(stage);
        return objective;
    }

    private StageDefinition stage(String id, String displayName) {
        StageDefinition stage = new StageDefinition();
        stage.setId(id);
        stage.setDisplayName(displayName);
        return stage;
    }
}
