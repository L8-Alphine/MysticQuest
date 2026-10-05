package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestHudViewModelTest {
    @Test
    void groupedQuestProjectsOnlyTheImmediateObjective() {
        ObjectiveView complete = ObjectiveView.of("enter", "Enter the western ruins", 1, 1);
        ObjectiveView primary = ObjectiveView.of("keys", "Recover the hidden keys", 2, 4);
        ObjectiveView supporting = ObjectiveView.of("inscription", "Decode the inscription", 0, 1);
        StageView stage = new StageView(
                "hidden_keys", "Echoes at the Temple", 2, 3,
                List.of(complete, primary, supporting));
        JournalEntry entry = new JournalEntry(
                "first_breach", "The First Breach", "", List.of(complete, primary, supporting),
                List.of(new StageView("arrival", "Arrival", 1, 3, List.of(complete)), stage), false);

        QuestHudViewModel model = QuestHudViewModel.from(entry);

        assertEquals("Recover the hidden keys", model.objectiveText());
        assertEquals("2 / 4", model.objectiveProgressLabel());
        assertEquals(0.5f, model.objectiveProgressRatio());
        assertEquals("STEP 2 OF 3  |  Echoes at the Temple", model.contextLabel());
        assertEquals("1 supporting objective in this step", model.guidanceLabel());
        assertEquals("QUEST 1 / 3", model.questProgressLabel());
        assertEquals(2, model.supportingObjectives().size());
        assertEquals("Decode the inscription", model.supportingObjectives().getFirst().objectiveText());
        assertEquals("SUPPORTING", model.supportingObjectives().getFirst().roleLabel());
        assertEquals("OPEN JOURNAL  /journal", model.actionHint());
    }

    @Test
    void completeEmptyQuestHasExplicitSemanticState() {
        JournalEntry entry = new JournalEntry(
                "epilogue", "Epilogue", "", List.of(), List.of(), true);

        QuestHudViewModel model = QuestHudViewModel.from(entry);

        assertEquals("COMPLETE", model.categoryLabel());
        assertEquals("DONE", model.stateLabel());
        assertEquals("Quest complete", model.objectiveText());
        assertEquals(1.0f, model.objectiveProgressRatio());
        assertTrue(model.guidanceLabel().contains("complete"));
    }

    @Test
    void aFinishedQuestAsksThePlayerToAct() {
        JournalEntry entry = JournalEntry.ungrouped(
                "first_breach", "The First Breach", "",
                List.of(ObjectiveView.of("seal", "Break the seal", 1, 1)), true);

        QuestHudViewModel model = QuestHudViewModel.from(entry);

        assertEquals(QuestHudViewModel.PriorityClass.ACTION_REQUIRED, model.priorityClass());
        assertEquals("seal", model.primaryObjectiveId());
    }

    @Test
    void guidanceCanBeReplacedWithoutTouchingTheRestOfTheSnapshot() {
        JournalEntry entry = JournalEntry.ungrouped(
                "first_breach", "The First Breach", "",
                List.of(ObjectiveView.of("keys", "Recover the hidden keys", 0, 4)), false);
        QuestHudViewModel model = QuestHudViewModel.from(entry);

        QuestHudViewModel guided = model.withGuidance("Marked on your map: Temple");

        assertEquals("Marked on your map: Temple", guided.guidanceLabel());
        assertEquals(model.withGuidance(model.guidanceLabel()), model);
        assertEquals("first_breach", guided.questId());
        assertEquals("keys", guided.primaryObjectiveId());
    }
}
