package org.hyzionstudios.mysticquests.external;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.ui.QuestHudViewModel;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalSignalsAndJournalTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000002");

    @Test
    void baselinePublishesNothing() {
        ExternalQuestBook book = new ExternalQuestBook();
        ExternalQuestBook.Change first = book.put(PLAYER,
                ExternalQuestBookTest.snapshot("endgame", List.of("a"), List.of(), Set.of("old"))).orElseThrow();
        assertTrue(ExternalSignals.of(first).isEmpty(), "logging in never replays history");
    }

    @Test
    void publishesSpecificAndGenericSignals() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, new ExternalQuestSnapshot("ziggfreed", List.of(), List.of(), Set.of(), Map.of("level.mining", 4L)));
        ExternalQuestBook.Change change = book.put(PLAYER, new ExternalQuestSnapshot("ziggfreed",
                List.of(ExternalQuestBookTest.quest("ziggfreed", "Getting_Started")), List.of(), Set.of("Into_The_Fray"),
                Map.of("level.mining", 6L))).orElseThrow();

        List<ExternalSignals.Signal> signals = ExternalSignals.of(change);
        assertTrue(signals.contains(new ExternalSignals.Signal("ziggfreed:quest_accepted:Getting_Started", 1)));
        assertTrue(signals.contains(new ExternalSignals.Signal("ziggfreed:quest_accepted", 1)));
        assertTrue(signals.contains(new ExternalSignals.Signal("ziggfreed:quest_completed:Into_The_Fray", 1)));
        assertTrue(signals.contains(new ExternalSignals.Signal("ziggfreed:quest_completed", 1)));
        assertTrue(signals.contains(new ExternalSignals.Signal("ziggfreed:stat:level.mining", 2)));
    }

    @Test
    void readyQuestLeadsWithItsNextAction() {
        ExternalQuest ready = new ExternalQuest("endgame", "story_merchant", "The Merchant", "Find Vorthak.", "endgame",
                2, 3, "Prologue",
                List.of(ExternalObjective.of("talk_to_0", "Talk to Vorthak", 1, 1)),
                ExternalQuest.Status.READY, "Return to Vorthak", "100 XP");
        JournalEntry entry = ExternalJournal.entry(ready);

        assertEquals("ext:endgame:story_merchant", entry.questId());
        assertFalse(entry.complete(), "handing in is still the player's to do");
        assertTrue(entry.grouped());
        assertEquals("STEP 2 OF 3", entry.currentStage().stepLabel());

        QuestHudViewModel model = QuestHudViewModel.from(entry);
        assertEquals("Return to Vorthak", model.objectiveText());
        assertEquals("The Merchant", model.questTitle());
    }

    @Test
    void questFinishedByTheModItselfReadsComplete() {
        ExternalQuest unclaimed = new ExternalQuest("ziggfreed", "q", "Quest", "", "mmo", 1, 1, "",
                List.of(ExternalObjective.of("a", "Mine ore", 5, 5)), ExternalQuest.Status.READY, "", "");
        JournalEntry entry = ExternalJournal.entry(unclaimed);
        assertTrue(entry.complete());
        assertFalse(entry.grouped(), "a quest without steps has no step furniture");
    }

    @Test
    void finishedQuestReadsLikeAFinishedQuestOfOurOwn() {
        JournalEntry entry = ExternalJournal.finishedEntry(
                new ExternalQuestBook.Finished("endgame", "story_echo", "Echoes of the Deep", null));

        assertEquals("ext:endgame:story_echo", entry.questId());
        assertEquals("Echoes of the Deep", entry.displayName());
        assertTrue(entry.complete());
        assertFalse(entry.grouped());
        assertEquals("Completed", entry.objectives().getFirst().displayName());
    }

    @Test
    void objectiveCountsAreClamped() {
        ExternalObjective objective = ExternalObjective.of("a", " Kill bosses ", 7, 2);
        assertEquals(2, objective.current());
        assertTrue(objective.complete());
        assertEquals("Kill bosses", objective.text());
    }
}
