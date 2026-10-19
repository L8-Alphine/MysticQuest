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
    void anotherTrackedQuestShowsItsCurrentObjective() {
        ObjectiveView done = ObjectiveView.of("enter", "Enter the western ruins", 1, 1);
        ObjectiveView keys = ObjectiveView.of("keys", "Recover the hidden keys", 2, 4);
        JournalEntry entry = new JournalEntry(
                "first_breach", "The First Breach", "", List.of(done, keys),
                List.of(new StageView("arrival", "Arrival", 1, 2, List.of(done)),
                        new StageView("keys", "Keys", 2, 2, List.of(keys))), false);

        QuestHudViewModel.TrackedRow row = QuestHudViewModel.TrackedRow.of(entry, "STORY");

        assertEquals(new QuestHudViewModel.TrackedRow("first_breach", "STORY", "The First Breach",
                "Recover the hidden keys", "2 / 4"), row);
        QuestHudViewModel.TrackedRow empty = QuestHudViewModel.TrackedRow.of(
                new JournalEntry("epilogue", "Epilogue", "", List.of(), List.of(), false), "QUEST");
        assertEquals("Awaiting objective", empty.objectiveText());
        assertEquals("", empty.progressLabel(), "a note has no count");
    }

    @Test
    void otherTrackedQuestsLeaveOutTheShownOneAndStopAtThree() {
        QuestHudViewModel model = QuestHudViewModel.from(JournalEntry.ungrouped(
                "ext:endgame:story_echo", "Echoes", "", List.of(ObjectiveView.of("a", "Dive", 0, 1)), false));
        assertTrue(model.otherTracked().isEmpty());

        QuestHudViewModel withRows = model.withOtherTracked(List.of(
                row("own_quest"), row("ext:endgame:story_echo"), row("ext:endgame:side_ore"), row("own_quest"),
                row("ext:ziggfreed:mining"), row("ext:ziggfreed:taming")));

        assertEquals(List.of("own_quest", "ext:endgame:side_ore", "ext:ziggfreed:mining"),
                withRows.otherTracked().stream().map(QuestHudViewModel.TrackedRow::questId).toList(),
                "the shown quest and repeats are dropped, the order kept, three at most");
        QuestHudViewModel restyled = withRows.withDisplayMode(QuestHudViewModel.DisplayMode.EXPANDED)
                .withCategory("ENDGAME").withGuidance("Somewhere");
        assertEquals(withRows.otherTracked(), restyled.otherTracked(), "the rows survive every other change");
    }

    private static QuestHudViewModel.TrackedRow row(String questId) {
        return new QuestHudViewModel.TrackedRow(questId, "QUEST", questId, "Do it", "0 / 1");
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
