package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redesign Bible §7.3: what the quest board shows, and what it keeps quiet. */
final class QuestBoardModelTest {
    private static final ObjectMapper JSON = Json.createMapper();

    private static QuestDefinition definition(String json) throws Exception {
        return JSON.readValue(json, QuestDefinition.class);
    }

    private static JournalEntry quest(String id, String name) {
        return JournalEntry.ungrouped(id, name, "", List.of(ObjectiveView.of("a", "A", 0, 1), ObjectiveView.of("b", "B", 0, 1)), false);
    }

    @Test
    void cardsAreGroupedAndShowOnlyWhatTheAuthorProvided() throws Exception {
        Map<String, QuestDefinition> definitions = Map.of(
                "q:grove", definition("""
                        { "id": "grove", "category": "story", "difficulty": "Hard", "partySize": 3, "rewardText": "A grove relic" }"""),
                "q:wolves", definition("""
                        { "id": "wolves", "category": "side" }"""),
                "q:vault", definition("""
                        { "id": "vault", "category": "story", "lockedText": "Break the grove seal first." }"""));
        QuestBoardModel board = new QuestBoardModel(List.of(quest("q:wolves", "Wolf Trouble"), quest("q:grove", "The Sealed Grove")),
                List.of(quest("q:vault", "The Vault")), id -> Optional.ofNullable(definitions.get(id)));

        assertEquals(List.of("STORY QUESTS", "SIDE QUESTS", "NOT YET AVAILABLE"),
                board.sections().stream().map(QuestBoardModel.Section::title).toList());
        QuestBoardModel.Card grove = board.sections().getFirst().cards().getFirst();
        assertEquals("2 objectives  |  Hard  |  Party of 3", grove.meta());
        assertEquals(List.of("DIFFICULTY", "PARTY", "OBJECTIVES", "REWARDS"), grove.facts().stream().map(QuestBoardModel.Fact::label).toList());

        QuestBoardModel.Card wolves = board.sections().get(1).cards().getFirst();
        assertEquals("2 objectives", wolves.meta(), "no difficulty or party size was written, so none is shown");
        assertTrue(wolves.facts().stream().noneMatch(fact -> fact.label().equals("REWARDS")), "unmentioned rewards stay a surprise");

        QuestBoardModel.Card vault = board.sections().getLast().cards().getFirst();
        assertTrue(vault.locked());
        assertEquals("Break the grove seal first.", vault.lockedText(), "a locked card shows only the public requirement");
        assertEquals(2, board.availableCount());
    }

    @Test
    void theBoardOpensOnAnAvailableQuestNotALockedOne() throws Exception {
        QuestDefinition vault = definition("""
                { "id": "vault", "lockedText": "Later." }""");
        QuestBoardModel board = new QuestBoardModel(List.of(quest("q:wolves", "Wolf Trouble")), List.of(quest("q:vault", "The Vault")),
                id -> id.equals("q:vault") ? Optional.of(vault) : Optional.empty());
        assertEquals("q:wolves", board.select(null).orElseThrow().entry().questId());
        assertEquals("q:vault", board.select("q:vault").orElseThrow().entry().questId(), "a locked quest can still be read");
        assertEquals("q:wolves", board.select("q:gone").orElseThrow().entry().questId());
    }
}
