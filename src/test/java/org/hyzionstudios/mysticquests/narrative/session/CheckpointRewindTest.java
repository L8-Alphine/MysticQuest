package org.hyzionstudios.mysticquests.narrative.session;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §21.1 interventions: "Advance / rewind to safe checkpoint" rewinds state without repeating
 * permanent effects, and "Restart node / act" starts a story over from a fresh session.
 */
final class CheckpointRewindTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private final NarrativeTestKit kit = new NarrativeTestKit();
    private final AtomicInteger rewards = new AtomicInteger();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private QuestValue phase(String sessionId) {
        QuestSession session = kit.runtime().sessions().get(sessionId).orElseThrow();
        return kit.runtime().variables()
                .get(kit.runtime().audiences().context(ALICE, session, null), null, id("hyzion:temple.phase")).orElseThrow();
    }

    @Test
    void rewindingRestoresTheSessionAndReplaysWithoutRepeatingRewards() {
        kit.runtime().actionTypes().register(id("test:reward"), (context, parameters) -> {
            rewards.incrementAndGet();
            return ActionResult.success();
        });
        kit.load("""
                {
                  "variableSchemas": [ { "id": "hyzion:temple.phase", "type": "string", "scope": "quest_session", "default": "closed" } ],
                  "puzzles": [ { "id": "hyzion:temple.lever", "story": "hyzion:temple", "inputs": ["pull"], "rule": "any",
                    "outputs": [
                      { "type": "mysticquests:checkpoint", "label": "before_open" },
                      { "type": "test:reward", "permanent": true },
                      { "type": "mysticquests:variable.set", "variable": "hyzion:temple.phase", "value": "open" } ] } ]
                }
                """);
        String sessionId = kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "pull", true).sessionId();
        assertEquals(new QuestValue.StringValue("open"), phase(sessionId));
        assertEquals(1, rewards.get());

        assertEquals(Optional.of(sessionId), kit.runtime().rewind(ALICE, "before_open"));
        assertEquals(new QuestValue.StringValue("closed"), phase(sessionId), "state is back at the checkpoint");

        kit.runtime().puzzles().resumePending(ALICE);
        assertEquals(new QuestValue.StringValue("open"), phase(sessionId), "the story replays from the checkpoint");
        assertEquals(1, rewards.get(), "but the permanent reward is not given twice");
        assertEquals(Outcome.IGNORED_COMPLETED, kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "pull", true).outcome());

        assertTrue(kit.runtime().rewind(ALICE, "no_such_label").isEmpty());
    }

    @Test
    void restartingAStoryStartsItFreshAndKeepsTheOldSession() {
        kit.load("""
                {
                  "variableSchemas": [ { "id": "hyzion:temple.phase", "type": "string", "scope": "quest_session", "default": "closed" } ],
                  "puzzles": [ { "id": "hyzion:temple.lever", "story": "hyzion:temple", "inputs": ["pull"], "rule": "any",
                    "outputs": [ { "type": "mysticquests:variable.set", "variable": "hyzion:temple.phase", "value": "open" } ] } ]
                }
                """);
        String first = kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "pull", true).sessionId();
        assertEquals(Optional.of(first), kit.runtime().restartStory(ALICE, "hyzion:temple"));
        assertEquals(SessionStatus.ABANDONED, kit.runtime().sessions().get(first).orElseThrow().status(), "kept for inspection");

        var again = kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "pull", true);
        assertEquals(Outcome.COMPLETED, again.outcome(), "the puzzle can be solved again");
        assertTrue(!again.sessionId().equals(first), "in a new session");
        assertTrue(kit.runtime().restartStory(ALICE, "hyzion:elsewhere").isEmpty());
    }
}
