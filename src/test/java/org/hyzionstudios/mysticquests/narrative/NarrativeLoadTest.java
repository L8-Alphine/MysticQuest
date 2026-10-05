package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Counter;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.trigger.QuestTriggerService.Routed;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerEvent;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicLong;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §28 at scale: hundreds of puzzles and players, with event-driven evaluation that costs only what an
 * event touches, and restart recovery of every session.
 *
 * <p>The time bounds are deliberately generous (a slow CI machine must pass); what these tests pin
 * down is the work done, counted exactly, which does not depend on the machine.
 */
final class NarrativeLoadTest {
    private static final int PUZZLES = 200;
    private static final int INPUTS = 4;
    private static final Duration BUDGET = Duration.ofSeconds(20);

    private NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private static UUID player(int index) {
        return new UUID(0x4000L, index + 1L);
    }

    private static String volume(int puzzle, int input) {
        return "load:v" + puzzle + "_" + input;
    }

    /** {@code puzzles} single-story puzzles of {@code inputs} inputs each, all needed, optionally gated. */
    private static String content(int puzzles, int inputs, boolean gated) {
        List<String> defined = new ArrayList<>(puzzles);
        for (int puzzle = 0; puzzle < puzzles; puzzle++) {
            List<String> declared = new ArrayList<>(inputs);
            for (int input = 0; input < inputs; input++) {
                declared.add("{ \"id\": \"in" + input + "\", \"volume\": \"" + volume(puzzle, input) + "\" }");
            }
            defined.add("""
                    { "id": "load:p%d", "story": "load:story%d", "audience": "auto",
                      "inputs": [ %s ], "rule": "all", %s
                      "outputs": [ { "type": "mysticquests:tag.add", "tag": "load:solved" } ] }
                    """.formatted(puzzle, puzzle, String.join(",", declared), gated ? "\"requires\": { \"type\": \"load:counted\" }," : ""));
        }
        return """
                { "tagSchemas": [ { "id": "load:solved", "scope": "quest_session" } ],
                  "puzzles": [ %s ] }
                """.formatted(String.join(",", defined));
    }

    private Routed walk(UUID player, String volume) {
        return kit.runtime().triggers().handle(new TriggerEvent(volume, "ENTER", player, player, List.of()), "load");
    }

    @Test
    void anEventEvaluatesOnlyTheConditionsBoundToItsOwnVolume() {
        AtomicLong evaluated = new AtomicLong();
        kit.runtime().conditionTypes().register(id("load:counted"), (context, parameters) -> {
            evaluated.incrementAndGet();
            return true;
        });
        kit.load(content(PUZZLES, INPUTS, true));
        int players = 500;

        long started = System.nanoTime();
        for (int index = 0; index < players; index++) {
            int puzzle = index % PUZZLES;
            for (int input = 0; input < INPUTS; input++) {
                walk(player(index), volume(puzzle, input));
            }
        }
        Duration took = Duration.ofNanos(System.nanoTime() - started);

        int events = players * INPUTS;
        assertEquals(events, evaluated.get(),
                "one requirement check per input event, not one per puzzle or per player (" + PUZZLES + " puzzles, " + players + " players)");
        NarrativeMetrics metrics = kit.runtime().metrics();
        assertEquals(events, metrics.count(Counter.TRIGGER_EVENTS));
        assertEquals(events, metrics.count(Counter.PUZZLE_INPUTS));
        assertEquals(players, metrics.count(Counter.TRANSITIONS_COMPLETED), "every audience solved once; empty transitions are not counted");
        assertEquals(players, kit.runtime().sessions().loaded().size(), "one session per audience");
        assertTrue(kit.problems.stream().noneMatch(problem -> problem.contains("FAILED")), kit.problems.toString());
        assertTrue(took.compareTo(BUDGET) < 0, events + " events took " + took.toMillis() + "ms");

        long before = evaluated.get();
        Routed unbound = walk(player(0), "load:nowhere");
        assertTrue(unbound.puzzleInputs().isEmpty());
        assertEquals(before, evaluated.get(), "a volume no puzzle uses evaluates nothing");
    }

    @Test
    void everySessionIsRestoredAfterARestartAndCarriesOn() {
        kit.load(content(PUZZLES, 2, false));
        int players = 1000;
        for (int index = 0; index < players; index++) {
            walk(player(index), volume(index % PUZZLES, 0));
        }
        kit.runtime().flush();

        kit.restart();
        long started = System.nanoTime();
        for (int index = 0; index < players; index++) {
            kit.runtime().onJoin(player(index));
        }
        Duration rejoin = Duration.ofNanos(System.nanoTime() - started);
        assertEquals(players, kit.runtime().metrics().count(Counter.SESSIONS_RESTORED), "every stored session is read back once");

        for (int index = 0; index < players; index++) {
            Routed second = walk(player(index), volume(index % PUZZLES, 1));
            assertEquals(Outcome.COMPLETED, second.puzzleInputs().getFirst().outcome(),
                    "the first input survived the restart for player " + index);
        }
        assertTrue(rejoin.compareTo(BUDGET) < 0, players + " joins took " + rejoin.toMillis() + "ms");
        assertTrue(kit.runtime().sessions().quarantined().isEmpty());
    }
}
