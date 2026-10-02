package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.InputStatus;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.PuzzleView;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.trigger.QuestTriggerService.Routed;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerEvent;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.IntStream;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §26.3 "Four-of-ten Druid puzzle": each player or party receives exactly four active, persisted
 * locations; unrelated locations do nothing; completion fires the configured outputs once. Also covers
 * §26.2 "reconnect during puzzle with 3/4 keys complete" and §26.3 "Restart recovery".
 */
final class FourOfTenPuzzleAcceptanceTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final NamespacedId PUZZLE = id("hyzion:druid_temple.hidden_keys");
    private static final NamespacedId PAYOUTS = id("hyzion:druid_temple.payouts");
    private static final List<String> KEYS = IntStream.rangeClosed(1, 10).mapToObj(index -> "key_" + index).toList();

    private NarrativeTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new NarrativeTestKit();
        StringBuilder inputs = new StringBuilder();
        StringBuilder volumes = new StringBuilder();
        for (String key : KEYS) {
            inputs.append(inputs.isEmpty() ? "" : ",").append("{ \"id\": \"").append(key).append("\", \"volume\": \"").append(volume(key)).append("\" }");
            volumes.append(volumes.isEmpty() ? "" : ",").append('"').append(volume(key)).append('"');
        }
        kit.load("""
                {
                  "tagSchemas": [ { "id": "hyzion:druid_temple.keys_complete", "scope": "quest_session" } ],
                  "variableSchemas": [
                    { "id": "hyzion:druid_temple.payouts", "type": "integer", "scope": "quest_session", "default": 0 }
                  ],
                  "puzzles": [ {
                    "id": "hyzion:druid_temple.hidden_keys",
                    "story": "hyzion:druid_temple",
                    "audience": "auto",
                    "inputs": [ %s ],
                    "selection": { "active": 4 },
                    "rule": "all",
                    "outputs": [
                      { "type": "mysticquests:trigger.disable", "volumes": [ %s ] },
                      { "type": "mysticquests:tag.add", "tag": "hyzion:druid_temple.keys_complete" },
                      { "type": "mysticquests:variable.increment", "variable": "hyzion:druid_temple.payouts" },
                      { "type": "mysticquests:signal", "signal": "hyzion:druid_temple.keys_complete" }
                    ]
                  } ]
                }
                """.formatted(inputs, volumes));
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private static String volume(String key) {
        return "avalon:druid_" + key;
    }

    private QuestPuzzleService puzzles() {
        return kit.runtime().puzzles();
    }

    /** Opens the audience's session, which makes the persisted selection, and returns it. */
    private List<String> activeKeysOf(UUID player) {
        puzzles().inputStatus(player, PUZZLE, KEYS.getFirst());
        return puzzles().view(player, PUZZLE).orElseThrow().activeInputs();
    }

    private Routed walkInto(UUID player, String key) {
        return kit.runtime().triggers().handle(new TriggerEvent(volume(key), "ENTER", player, player, List.of()), "avalon");
    }

    private int payouts(UUID player) {
        String session = puzzles().view(player, PUZZLE).orElseThrow().sessionId();
        return ((IntValue) kit.runtime().variables()
                .get(ScopeContext.player(player).withSession(session, "hyzion:druid_temple"), null, PAYOUTS).orElseThrow()).value();
    }

    @Test
    void everyAudienceGetsExactlyFourDistinctPersistedKeys() {
        for (UUID player : List.of(ALICE, BOB)) {
            List<String> active = activeKeysOf(player);
            assertEquals(4, active.size());
            assertEquals(4, new HashSet<>(active).size(), "distinct");
            assertTrue(KEYS.containsAll(active));
            assertEquals(active, activeKeysOf(player), "asking again never rerolls");
        }
    }

    @Test
    void unselectedLocationsDoNothingForThatAudience() {
        List<String> active = activeKeysOf(ALICE);
        String decoy = KEYS.stream().filter(key -> !active.contains(key)).findFirst().orElseThrow();
        assertEquals(InputStatus.INACTIVE, puzzles().inputStatus(ALICE, PUZZLE, decoy));
        Routed routed = walkInto(ALICE, decoy);
        assertEquals(Outcome.IGNORED_INACTIVE, routed.puzzleInputs().getFirst().outcome());
        assertTrue(puzzles().view(ALICE, PUZZLE).orElseThrow().activated().isEmpty());
    }

    @Test
    void reconnectAndRestartWithThreeOfFourKeysThenCompleteExactlyOnce() {
        List<String> active = activeKeysOf(ALICE);
        for (String key : active.subList(0, 3)) {
            assertEquals(Outcome.ACCEPTED, walkInto(ALICE, key).puzzleInputs().getFirst().outcome());
        }
        assertEquals(InputStatus.ACTIVATED, puzzles().inputStatus(ALICE, PUZZLE, active.getFirst()));

        // Reconnect: quit writes the session out and unloads it; join restores it.
        String sessionId = puzzles().view(ALICE, PUZZLE).orElseThrow().sessionId();
        kit.runtime().onQuit(ALICE, Optional.empty(), false);
        assertTrue(kit.runtime().sessions().get(sessionId).isEmpty(), "unloaded on quit");
        kit.runtime().onJoin(ALICE);
        assertTrue(kit.runtime().sessions().get(sessionId).isPresent(), "restored on join");
        // Restart: a fresh runtime over the same storage.
        kit.runtime().flush();
        kit.restart();
        kit.runtime().onJoin(ALICE);

        PuzzleView resumed = puzzles().view(ALICE, PUZZLE).orElseThrow();
        assertEquals(active, resumed.activeInputs(), "the selection is never rerolled");
        assertEquals(Set.copyOf(active.subList(0, 3)), resumed.activated().keySet(), "three keys still collected");
        assertEquals(0, payouts(ALICE));

        Routed last = walkInto(ALICE, active.get(3));
        assertEquals(Outcome.COMPLETED, last.puzzleInputs().getFirst().outcome());
        assertEquals(1, payouts(ALICE));
        assertEquals(1, kit.signals.size());

        // Outputs fire once: more input, a rejoin and another restart change nothing.
        assertEquals(Outcome.IGNORED_COMPLETED, puzzles().input(ALICE, "avalon", PUZZLE, active.get(3), true).outcome());
        kit.runtime().onJoin(ALICE);
        kit.runtime().flush();
        kit.restart();
        kit.runtime().onJoin(ALICE);
        assertEquals(1, payouts(ALICE));
        assertEquals(1, kit.signals.size());
        assertTrue(puzzles().view(ALICE, PUZZLE).orElseThrow().outputsComplete());
    }

    @Test
    void completingDisablesTheKeyVolumesForThatSessionOnly() {
        for (String key : activeKeysOf(ALICE)) {
            walkInto(ALICE, key);
        }
        String someKey = KEYS.getFirst();
        assertFalse(kit.runtime().activation().isEnabled(volume(someKey), ALICE));
        assertTrue(kit.runtime().activation().isEnabled(volume(someKey), BOB), "Bob's hunt is unaffected");
        assertFalse(walkInto(ALICE, someKey).enabled());
        activeKeysOf(BOB);
        assertTrue(walkInto(BOB, someKey).enabled());
    }

    @Test
    void aPartySharesOneSelectionAndOnePayout() {
        kit.parties.put(ALICE, "p1");
        kit.parties.put(BOB, "p1");
        List<String> active = activeKeysOf(ALICE);
        assertEquals(active, activeKeysOf(BOB), "one party, one selection");
        walkInto(ALICE, active.get(0));
        walkInto(BOB, active.get(1));
        walkInto(ALICE, active.get(2));
        assertEquals(Outcome.IGNORED_DUPLICATE, walkInto(BOB, active.get(2)).puzzleInputs().getFirst().outcome());
        assertEquals(Outcome.COMPLETED, walkInto(BOB, active.get(3)).puzzleInputs().getFirst().outcome());
        assertEquals(1, payouts(ALICE));
        assertEquals(SessionOwner.party("p1"), puzzles().view(BOB, PUZZLE).orElseThrow().owner());
    }

    /** §22: a reward that cannot land while the player is away lands when they return, once. */
    @Test
    void anOutputThatFailsIsRetriedOnJoinWithoutRepeatingTheOthers() {
        List<ActionResult> rewardResults = new ArrayList<>(List.of(ActionResult.retryable("player offline")));
        List<String> paid = new ArrayList<>();
        kit.runtime().actionTypes().register(id("test:reward"), (context, parameters) -> {
            ActionResult result = rewardResults.getFirst();
            if (result.status().settled()) {
                paid.add("reward");
            }
            return result;
        });
        kit.load("""
                {
                  "variableSchemas": [ { "id": "hyzion:shrine.payouts", "type": "integer", "scope": "quest_session", "default": 0 } ],
                  "puzzles": [ {
                    "id": "hyzion:shrine", "inputs": ["bell"], "rule": "any",
                    "outputs": [
                      { "type": "mysticquests:variable.increment", "variable": "hyzion:shrine.payouts" },
                      { "type": "test:reward" }
                    ]
                  } ]
                }
                """);
        QuestPuzzleService.InputResult result = puzzles().input(ALICE, null, id("hyzion:shrine"), "bell", true);
        assertEquals(Outcome.COMPLETED, result.outcome());
        assertFalse(result.transition().complete());
        assertFalse(puzzles().view(ALICE, id("hyzion:shrine")).orElseThrow().outputsComplete());

        rewardResults.set(0, ActionResult.success());
        kit.runtime().onJoin(ALICE);
        assertEquals(List.of("reward"), paid);
        assertTrue(puzzles().view(ALICE, id("hyzion:shrine")).orElseThrow().outputsComplete());
        String session = puzzles().view(ALICE, id("hyzion:shrine")).orElseThrow().sessionId();
        assertEquals(new IntValue(1), kit.runtime().variables()
                .get(ScopeContext.player(ALICE).withSession(session, "hyzion:shrine"), null, id("hyzion:shrine.payouts"))
                .orElseThrow(), "the increment before the failed reward did not run twice");
    }

    @Test
    void resetWithRerollDrawsANewPersistedSelection() {
        List<String> before = activeKeysOf(ALICE);
        walkInto(ALICE, before.getFirst());
        puzzles().reset(ALICE, PUZZLE, false, "test");
        PuzzleView kept = puzzles().view(ALICE, PUZZLE).orElseThrow();
        assertEquals(before, kept.activeInputs(), "a plain reset keeps the selection");
        assertTrue(kept.activated().isEmpty());
        assertEquals(1, kept.generation());

        boolean changed = false;
        for (int attempt = 0; attempt < 5 && !changed; attempt++) {
            puzzles().reset(ALICE, PUZZLE, true, "test");
            changed = !puzzles().view(ALICE, PUZZLE).orElseThrow().activeInputs().equals(before);
        }
        assertTrue(changed, "rerolling draws again (five draws all equal has probability under 1e-11)");
    }

    @Test
    void selectionIsDeterministicPerSeedAndUnbiasedInShape() {
        long seed = PuzzleSelector.seed("hyzion:p", "qs-1", 0);
        assertEquals(seed, PuzzleSelector.seed("hyzion:p", "qs-1", 0));
        assertNotEquals(seed, PuzzleSelector.seed("hyzion:p", "qs-1", 1));
        List<String> first = PuzzleSelector.select(KEYS, 4, seed);
        assertEquals(first, PuzzleSelector.select(KEYS, 4, seed));
        assertEquals(first.stream().sorted((a, b) -> KEYS.indexOf(a) - KEYS.indexOf(b)).toList(), first,
                "kept in authored order");

        int[] hits = new int[KEYS.size()];
        for (int round = 0; round < 10_000; round++) {
            for (String key : PuzzleSelector.select(KEYS, 4, PuzzleSelector.seed("hyzion:p", "qs-" + round, 0))) {
                hits[KEYS.indexOf(key)]++;
            }
        }
        for (int count : hits) {
            assertTrue(count > 3_600 && count < 4_400, "each key is chosen about 40% of the time, got " + count);
        }
    }
}
