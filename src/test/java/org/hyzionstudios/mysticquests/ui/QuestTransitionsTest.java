package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;
import org.hyzionstudios.mysticquests.ui.QuestTransitions.Kind;
import org.hyzionstudios.mysticquests.ui.QuestTransitions.Snapshot;
import org.hyzionstudios.mysticquests.ui.QuestTransitions.Transition;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestTransitionsTest {
    private static final Instant EARLIER = Instant.parse("2026-10-01T10:00:00Z");
    private static final Instant NOW = Instant.parse("2026-10-04T10:00:00Z");

    @Test
    void anUnchangedJournalAnnouncesNothing() {
        Snapshot snapshot = Snapshot.of(List.of(wolves(2)), Map.of());

        assertTrue(QuestTransitions.between(snapshot, snapshot).isEmpty());
    }

    @Test
    void aNewActiveQuestIsAcceptedWithItsFirstObjective() {
        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(), Map.of()),
                Snapshot.of(List.of(wolves(0)), Map.of()));

        assertEquals(1, transitions.size());
        assertEquals(Kind.ACCEPTED, transitions.getFirst().kind());
        assertEquals("Thin the Pack", transitions.getFirst().questTitle());
        assertEquals("Slay wolves", transitions.getFirst().detail());
    }

    @Test
    void progressNamesTheObjectiveAndItsCountUnderAProgressTag() {
        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(wolves(2)), Map.of()),
                Snapshot.of(List.of(wolves(3)), Map.of()));

        Transition progress = transitions.getFirst();
        assertEquals(Kind.PROGRESS, progress.kind());
        assertEquals("Slay wolves  3 / 5", progress.detail());
        assertEquals("mq.quest.hunt:wolves.progress", progress.tag());
    }

    @Test
    void reachingANewStepIsAStepNotProgress() {
        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(temple(false)), Map.of()),
                Snapshot.of(List.of(temple(true)), Map.of()));

        assertEquals(1, transitions.size());
        assertEquals(Kind.STEP, transitions.getFirst().kind());
        assertEquals("STEP 2 OF 2  |  Echoes at the Temple", transitions.getFirst().detail());
    }

    @Test
    void completionReplacesTheQuestsStateToastAndComesBeforeTheNextChapter() {
        JournalEntry next = JournalEntry.ungrouped("hunt:den", "The Den", "",
                List.of(ObjectiveView.of("den", "Find the den", 0, 1)), false);

        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(wolves(4)), Map.of()),
                Snapshot.of(List.of(next), Map.of("hunt:wolves", NOW)));

        assertEquals(List.of(Kind.COMPLETED, Kind.ACCEPTED), transitions.stream().map(Transition::kind).toList());
        Transition completed = transitions.getFirst();
        assertEquals("Thin the Pack", completed.detail());
        assertEquals("mq.quest.hunt:wolves.state", completed.tag());
        assertNotEquals(completed.tag(), new Transition(Kind.PROGRESS, "hunt:wolves", "", "").tag());
    }

    @Test
    void abandoningAQuestCompletedOnceBeforeIsNotACompletion() {
        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(wolves(1)), Map.of("hunt:wolves", EARLIER)),
                Snapshot.of(List.of(), Map.of("hunt:wolves", EARLIER)));

        assertTrue(transitions.isEmpty());
    }

    @Test
    void completingARepeatableQuestAgainIsAnnouncedAgain() {
        List<Transition> transitions = QuestTransitions.between(
                Snapshot.of(List.of(wolves(4)), Map.of("hunt:wolves", EARLIER)),
                Snapshot.of(List.of(), Map.of("hunt:wolves", NOW)));

        assertEquals(List.of(Kind.COMPLETED), transitions.stream().map(Transition::kind).toList());
    }

    private static JournalEntry wolves(int killed) {
        return JournalEntry.ungrouped("hunt:wolves", "Thin the Pack", "",
                List.of(ObjectiveView.of("wolves", "Slay wolves", killed, 5)), false);
    }

    private static JournalEntry temple(boolean arrived) {
        ObjectiveView enter = ObjectiveView.of("enter", "Enter the ruins", arrived ? 1 : 0, 1);
        ObjectiveView keys = ObjectiveView.of("keys", "Recover the keys", 0, 4);
        return new JournalEntry("story:temple", "The First Breach", "", List.of(enter, keys), List.of(
                new StageView("arrival", "Arrival", 1, 2, List.of(enter)),
                new StageView("keys", "Echoes at the Temple", 2, 2, List.of(keys))), false);
    }
}
