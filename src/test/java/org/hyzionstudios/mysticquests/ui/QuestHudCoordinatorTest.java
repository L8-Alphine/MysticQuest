package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestHudCoordinatorTest {
    private final UUID playerId = UUID.randomUUID();
    private final QuestHudCoordinator coordinator = new QuestHudCoordinator();

    @Test
    void automaticModeExpandsOnlyWhenTheCurrentStepHasContextToShow() {
        QuestHudCoordinator.Placement placement = coordinator.resolve(playerId, model(2), false);

        assertEquals(QuestHudViewModel.DisplayMode.EXPANDED, placement.mode());
        assertTrue(placement.tracker());
        assertFalse(placement.puzzle());

        assertEquals(
                QuestHudViewModel.DisplayMode.COMPACT,
                coordinator.resolve(playerId, model(1), false).mode());
    }

    @Test
    void screenPressureWinsOverAnExpandedPlayerPreference() {
        coordinator.setPreference(playerId, QuestHudCoordinator.Preference.EXPANDED);
        coordinator.setContext(playerId, new QuestHudCoordinator.Context(false, true));

        assertEquals(
                QuestHudViewModel.DisplayMode.COMPACT,
                coordinator.resolve(playerId, model(2), false).mode());
    }

    @Test
    void cinematicContextSuppressesEveryLayer() {
        coordinator.setContext(playerId, new QuestHudCoordinator.Context(true, false));

        QuestHudCoordinator.Placement placement = coordinator.resolve(playerId, model(2), true);

        assertFalse(placement.tracker());
        assertFalse(placement.puzzle());
    }

    @Test
    void leavingTheCinematicRestoresTheTracker() {
        coordinator.setContext(playerId, new QuestHudCoordinator.Context(true, false));
        coordinator.setContext(playerId, QuestHudCoordinator.Context.EXPLORATION);

        assertTrue(coordinator.resolve(playerId, model(1), false).tracker());
    }

    @Test
    void aHiddenTrackerStillLetsThePuzzleCardThrough() {
        coordinator.setPreference(playerId, QuestHudCoordinator.Preference.HIDDEN);

        assertFalse(coordinator.resolve(playerId, model(2), false).tracker(), "the player asked for no tracker");
        QuestHudCoordinator.Placement puzzle = coordinator.resolve(playerId, model(2), true);
        assertFalse(puzzle.tracker());
        assertTrue(puzzle.puzzle(), "the puzzle card is the puzzle's only feedback, so it still shows");
    }

    @Test
    void explicitExpandedPreferenceCanPromoteASingleObjectiveQuest() {
        coordinator.setPreference(playerId, QuestHudCoordinator.Preference.EXPANDED);

        assertEquals(
                QuestHudViewModel.DisplayMode.EXPANDED,
                coordinator.resolve(playerId, model(1), false).mode());
    }

    @Test
    void anActivePuzzleShowsItsCardAndStepsTheTrackerDownToCompact() {
        coordinator.setPreference(playerId, QuestHudCoordinator.Preference.EXPANDED);

        QuestHudCoordinator.Placement placement = coordinator.resolve(playerId, model(2), true);

        assertTrue(placement.puzzle());
        assertTrue(placement.tracker());
        assertEquals(QuestHudViewModel.DisplayMode.COMPACT, placement.mode());
    }

    @Test
    void thePuzzleCardDoesNotNeedATrackedQuest() {
        QuestHudCoordinator.Placement placement = coordinator.resolve(playerId, null, true);

        assertTrue(placement.puzzle());
        assertFalse(placement.tracker());
    }

    @Test
    void forgettingAPlayerDropsTheirPreference() {
        coordinator.setPreference(playerId, QuestHudCoordinator.Preference.COMPACT);
        coordinator.forget(playerId);

        assertEquals(QuestHudCoordinator.Preference.AUTOMATIC, coordinator.preference(playerId));
    }

    private QuestHudViewModel model(int objectiveCount) {
        List<ObjectiveView> objectives = java.util.stream.IntStream.range(0, objectiveCount)
                .mapToObj(index -> ObjectiveView.of("objective" + index, "Objective " + index, 0, 1))
                .toList();
        return QuestHudViewModel.from(JournalEntry.ungrouped(
                "quest", "Quest", "", objectives, false));
    }

    @Test
    void aCutsceneKeepsTheHudHiddenAfterAConversationInsideItEnds() {
        coordinator.setCinematic(playerId, "cutscene:s1", true);
        coordinator.setContext(playerId, new QuestHudCoordinator.Context(true, false));
        coordinator.setContext(playerId, QuestHudCoordinator.Context.EXPLORATION);

        assertFalse(coordinator.resolve(playerId, model(1), true).tracker(), "the scene still owns the screen");
        assertFalse(coordinator.resolve(playerId, model(1), true).puzzle());

        coordinator.setCinematic(playerId, "cutscene:s1", false);
        assertTrue(coordinator.resolve(playerId, model(1), false).tracker());
    }
}
