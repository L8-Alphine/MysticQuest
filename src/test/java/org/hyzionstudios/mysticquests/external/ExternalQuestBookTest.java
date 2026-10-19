package org.hyzionstudios.mysticquests.external;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ExternalQuestBookTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-0000-0000-000000000001");

    static ExternalQuest quest(String source, String id) {
        return new ExternalQuest(source, id, "Title " + id, "", source, 1, 1, "",
                List.of(ExternalObjective.of("o", "Do it", 0, 2)), ExternalQuest.Status.ACTIVE, "", "");
    }

    static ExternalQuestSnapshot snapshot(String source, List<String> active, List<String> tracked, Set<String> completed) {
        return new ExternalQuestSnapshot(source, active.stream().map(id -> quest(source, id)).toList(), tracked, completed, Map.of());
    }

    @Test
    void reportsOnlyRealChanges() {
        ExternalQuestBook book = new ExternalQuestBook();
        List<ExternalQuestBook.Change> seen = new ArrayList<>();
        book.onChange(seen::add);

        assertTrue(book.put(PLAYER, snapshot("endgame", List.of("a"), List.of(), Set.of())).isPresent());
        assertTrue(book.put(PLAYER, snapshot("endgame", List.of("a"), List.of(), Set.of())).isEmpty());
        assertEquals(1, seen.size());
        assertTrue(seen.getFirst().baseline(), "the first snapshot after a join is a baseline");
    }

    @Test
    void diffsAcceptedCompletedAndTracked() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, snapshot("endgame", List.of("a"), List.of("a"), Set.of()));
        ExternalQuestBook.Change change = book.put(PLAYER, snapshot("endgame", List.of("b"), List.of("b"), Set.of("a"))).orElseThrow();

        assertEquals(List.of("b"), change.newlyActive());
        assertEquals(List.of("a"), change.newlyCompleted());
        assertEquals(List.of("b"), change.newlyTracked());
        assertEquals(List.of("a"), change.newlyUntracked());
    }

    @Test
    void ownQuestWinsUntilTheyTrackAMirroredOne() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, snapshot("endgame", List.of("a"), List.of("a"), Set.of()));

        // Already tracked at join: no news, so the player's own quest keeps the tracker.
        assertTrue(book.trackerQuest(PLAYER, true).isEmpty());
        // With nothing of their own, the mirrored tracked quest shows.
        assertEquals("a", book.trackerQuest(PLAYER, false).orElseThrow().questId());

        // Tracking a quest in the other mod's book moves the tracker to it.
        book.put(PLAYER, snapshot("endgame", List.of("a", "b"), List.of("a", "b"), Set.of()));
        assertEquals("b", book.trackerQuest(PLAYER, true).orElseThrow().questId());

        // Picking their own quest again hands it back.
        book.focusMysticQuests(PLAYER);
        assertTrue(book.trackerQuest(PLAYER, true).isEmpty());
    }

    @Test
    void focusLeavesAQuestThatIsUntrackedOrFinished() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, snapshot("endgame", List.of("a"), List.of(), Set.of()));
        book.focus(PLAYER, ExternalQuestId.of("endgame", "a"));
        assertEquals("a", book.trackerQuest(PLAYER, true).orElseThrow().questId());

        book.put(PLAYER, snapshot("endgame", List.of(), List.of(), Set.of("a")));
        assertTrue(book.trackerQuest(PLAYER, true).isEmpty(), "a finished quest gives the tracker back");

        book.put(PLAYER, snapshot("endgame", List.of("c"), List.of("c"), Set.of("a")));
        assertEquals("c", book.trackerQuest(PLAYER, true).orElseThrow().questId());
        book.put(PLAYER, snapshot("endgame", List.of("c"), List.of(), Set.of("a")));
        assertTrue(book.trackerQuest(PLAYER, true).isEmpty(), "untracking it in the mod gives the tracker back");
    }

    @Test
    void answersConditionsFromTheLatestSnapshot() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, new ExternalQuestSnapshot("ziggfreed", List.of(quest("ziggfreed", "q")), List.of(), Set.of("done"),
                Map.of("level.mining", 12L)));

        assertTrue(book.isActive(PLAYER, "ziggfreed", "q"));
        assertTrue(book.isCompleted(PLAYER, "ziggfreed", "done"));
        assertFalse(book.isCompleted(PLAYER, "endgame", "done"));
        assertEquals(12L, book.stat(PLAYER, "ziggfreed", "level.mining").orElseThrow());
        assertTrue(book.find(PLAYER, "ext:ziggfreed:q").isPresent());
        assertNull(book.trackedKey(PLAYER, true));

        book.forget(PLAYER);
        assertTrue(book.snapshots(PLAYER).isEmpty());
    }

    @Test
    void listsEveryTrackedQuestModByModMostRecentFirst() {
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, snapshot("endgame", List.of("a", "b", "c"), List.of("a", "gone", "c", "a"), Set.of()));
        book.put(PLAYER, snapshot("ziggfreed", List.of("z"), List.of("z"), Set.of()));

        assertEquals(List.of("ext:endgame:c", "ext:endgame:a", "ext:ziggfreed:z"),
                book.tracked(PLAYER).stream().map(ExternalQuest::key).toList(),
                "an untracked quest is left out, one no longer active is skipped, a repeat is listed once");
        assertTrue(book.tracked(UUID.randomUUID()).isEmpty());
    }

    @Test
    void finishedQuestsCarryTheModsTitlesAndDates() {
        Instant monday = Instant.parse("2026-10-05T10:00:00Z");
        ExternalQuestBook book = new ExternalQuestBook();
        book.put(PLAYER, new ExternalQuestSnapshot("endgame", List.of(quest("endgame", "again")), List.of(),
                Set.of("story_echo", "side_ore", "again", "untitled"),
                Map.of("story_echo", new ExternalQuestSnapshot.Completion("Echoes of the Deep", monday),
                        "untitled", new ExternalQuestSnapshot.Completion("  ", monday),
                        "never_finished", new ExternalQuestSnapshot.Completion("Not Done", null)),
                Map.of()));

        List<ExternalQuestBook.Finished> finished = book.finished(PLAYER);
        assertEquals(List.of("Echoes of the Deep", "side_ore", "untitled"), finished.stream().map(ExternalQuestBook.Finished::title).toList(),
                "by title; without a title a quest reads as its id; one being done again is active, not finished");
        assertEquals(monday, finished.getFirst().at());
        assertEquals("ext:endgame:story_echo", finished.getFirst().key());
        assertNull(finished.get(1).at(), "the mod gave no date");
        assertEquals(monday, finished.get(2).at(), "a blank title still keeps its date");

        ExternalQuestSnapshot snapshot = book.snapshot(PLAYER, "endgame").orElseThrow();
        assertFalse(snapshot.completions().containsKey("never_finished"), "only finished quests keep a completion");
        assertEquals(snapshot, new ExternalQuestSnapshot("endgame", snapshot.active(), List.of(), snapshot.completed(),
                snapshot.completions(), Map.of()), "unchanged titles are no news");
    }

    @Test
    void titlesAreWordedOncePerLanguageAndContent() {
        TitleCache cache = new TitleCache();
        Object catalog = new Object();
        AtomicInteger worded = new AtomicInteger();
        java.util.function.Function<String, String> english = id -> {
            worded.incrementAndGet();
            return "Title of " + id;
        };

        assertEquals("Title of a", cache.title(catalog, "en-US", "a", english));
        assertEquals("Title of a", cache.title(catalog, "en-US", "a", english));
        assertEquals(1, worded.get());
        assertEquals("Titre de a", cache.title(catalog, "fr-FR", "a", id -> "Titre de " + id), "each language has its own words");
        assertEquals("", cache.title(catalog, "en-US", "b", id -> null));

        cache.title(new Object(), "en-US", "a", english);
        assertEquals(2, worded.get(), "reloaded content is worded again");
    }

    @Test
    void parsesKeys() {
        assertEquals("ext:endgame:contract/easy_1", ExternalQuestId.of("endgame", "contract/easy_1"));
        ExternalQuestId.Parsed parsed = ExternalQuestId.parse("ext:endgame:a:b").orElseThrow();
        assertEquals("endgame", parsed.source());
        assertEquals("a:b", parsed.questId());
        assertTrue(ExternalQuestId.parse("story_echo").isEmpty());
        assertTrue(ExternalQuestId.parse("ext:endgame:").isEmpty());
    }
}
