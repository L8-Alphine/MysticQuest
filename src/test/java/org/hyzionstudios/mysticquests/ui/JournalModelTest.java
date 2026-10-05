package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;

import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redesign Bible §7.1-7.2: what the Journal's rail holds, in what order, and what it opens on. */
final class JournalModelTest {
    private static final Instant MONDAY = Instant.parse("2026-10-05T10:00:00Z");

    private static JournalEntry quest(String id, String name, boolean complete) {
        return JournalEntry.ungrouped(id, name, "", List.of(ObjectiveView.of("o", "Do it", complete ? 1 : 0, 1)), complete);
    }

    private static final Map<String, String> CATEGORIES = Map.of(
            "q:wolves", "side", "q:grove", "story", "q:bounty", "contract", "q:plain", "");

    private static JournalModel model(String tracked) {
        List<JournalEntry> active = List.of(quest("q:wolves", "Wolf Trouble", false), quest("q:grove", "The Sealed Grove", false),
                quest("q:bounty", "Bounty", false), quest("q:plain", "Errand", false));
        List<JournalModel.Finished> completed = List.of(
                new JournalModel.Finished(quest("q:old", "Old News", true), MONDAY.minusSeconds(86_400 * 3)),
                new JournalModel.Finished(quest("q:recent", "Fresh Win", true), MONDAY));
        List<JournalSources.Story> stories = List.of(new JournalSources.Story("grove:sealed_grove", "Sealed Grove", true, MONDAY,
                List.of(new JournalSources.Milestone("Broke the grove seal", MONDAY))));
        return new JournalModel(active, tracked, Map.of("q:grove", MONDAY), completed, List.of(), stories,
                id -> CATEGORIES.getOrDefault(id, ""), id -> id.equals("q:grove") ? "A relic of the grove" : "");
    }

    @Test
    void theRailLeadsWithTheTrackedQuestThenCategoriesStoriesAndFinishedQuests() {
        JournalModel model = model("q:wolves");
        assertEquals(List.of("TRACKED", "STORY QUESTS", "QUESTS", "CONTRACTS", "STORIES", "COMPLETED"),
                model.sections().stream().map(JournalModel.Section::title).toList(),
                "story quests first, uncategorised next, then the rest in rail order; the tracked quest is not listed twice");
        assertEquals(List.of("Fresh Win", "Old News"),
                model.sections().getLast().items().stream().map(JournalModel.Item::title).toList(), "newest first");
        assertEquals(1, model.storyCount());
    }

    @Test
    void itOpensOnTheTrackedQuestAndKeepsASelectionWhileItExists() {
        JournalModel model = model("q:bounty");
        assertEquals("q:bounty", model.select(null, null).orElseThrow().id(), "opening from the HUD lands on the tracked quest");
        assertEquals("grove:sealed_grove", model.select(JournalModel.Kind.STORY, "grove:sealed_grove").orElseThrow().id());
        assertEquals("q:bounty", model.select(JournalModel.Kind.QUEST, "q:gone").orElseThrow().id(),
                "a selection that no longer exists falls back to the tracked quest");
        assertEquals("q:grove", model(null).select(null, null).orElseThrow().id(),
                "with nothing tracked, the first active quest in rail order: story quests come first");
    }

    @Test
    void detailsCarryRevealedRewardsAndAReadableTimeline() {
        JournalModel model = model("q:grove");
        JournalModel.QuestDetail grove = model.quest("q:grove").orElseThrow();
        assertEquals(JournalModel.State.TRACKED, grove.state());
        assertEquals("A relic of the grove", grove.rewardText());
        assertEquals(List.of(new JournalModel.Moment("5 Oct 2026", "Accepted")), grove.timeline());
        assertEquals("Since 5 Oct 2026", grove.when());

        JournalModel.QuestDetail won = model.quest("q:recent").orElseThrow();
        assertEquals(JournalModel.State.COMPLETE, won.state());
        assertEquals(List.of(new JournalModel.Moment("5 Oct 2026", "Completed")), won.timeline());
        assertTrue(model.quest("q:wolves").orElseThrow().rewardText().isEmpty(), "unmentioned rewards stay a surprise");
    }
}
