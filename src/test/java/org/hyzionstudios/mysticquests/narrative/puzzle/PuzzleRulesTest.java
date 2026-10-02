package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §10 and §26.1 "Puzzle rules and deterministic selection": every rule type, through the service. */
final class PuzzleRulesTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private void puzzle(String inputs, String rule, String extra) {
        kit.load("""
                {
                  "tagSchemas": [ { "id": "hyzion:solved", "scope": "quest_session" } ],
                  "puzzles": [ {
                    "id": "hyzion:p",
                    "inputs": %s,
                    "rule": %s,
                    %s
                    "outputs": [ { "type": "mysticquests:tag.add", "tag": "hyzion:solved" } ]
                  } ]
                }
                """.formatted(inputs, rule, extra));
    }

    private Outcome input(String input) {
        return kit.runtime().puzzles().input(ALICE, null, id("hyzion:p"), input, true).outcome();
    }

    private Outcome release(String input) {
        return kit.runtime().puzzles().input(ALICE, null, id("hyzion:p"), input, false).outcome();
    }

    @Test
    void allNeedsEveryInput() {
        puzzle("[\"a\",\"b\",\"c\"]", "\"all\"", "");
        assertEquals(Outcome.ACCEPTED, input("a"));
        assertEquals(Outcome.IGNORED_DUPLICATE, input("a"));
        assertEquals(Outcome.ACCEPTED, input("c"));
        assertEquals(Outcome.COMPLETED, input("b"));
        assertEquals(Outcome.IGNORED_COMPLETED, input("a"));
    }

    @Test
    void anyNeedsOne() {
        puzzle("[\"left\",\"right\"]", "\"any\"", "");
        assertEquals(Outcome.COMPLETED, input("right"));
    }

    @Test
    void nOfMCountsDistinctInputs() {
        puzzle("[\"a\",\"b\",\"c\",\"d\"]", "{ \"type\": \"n_of_m\", \"required\": 2 }", "");
        assertEquals(Outcome.ACCEPTED, input("d"));
        assertEquals(Outcome.IGNORED_DUPLICATE, input("d"));
        assertEquals(Outcome.COMPLETED, input("a"));
    }

    @Test
    void sequenceResetsOnAMistake() {
        puzzle("[\"fire\",\"water\",\"earth\",\"air\"]",
                "{ \"type\": \"sequence\", \"sequence\": [\"fire\",\"water\",\"earth\",\"air\"] }", "");
        assertEquals(Outcome.ACCEPTED, input("fire"));
        assertEquals(Outcome.ACCEPTED, input("water"));
        assertEquals(Outcome.MISTAKE, input("air"));
        assertEquals(Outcome.MISTAKE, input("water"), "progress was reset, so water is wrong now");
        assertEquals(Outcome.ACCEPTED, input("fire"));
        assertEquals(Outcome.ACCEPTED, input("water"));
        assertEquals(Outcome.ACCEPTED, input("earth"));
        assertEquals(Outcome.COMPLETED, input("air"));
    }

    @Test
    void unorderedSequenceTakesAnyOrderButOnlyTheListedInputsCount() {
        puzzle("[\"r1\",\"r2\",\"r3\",\"decoy\"]",
                "{ \"type\": \"unordered_sequence\", \"sequence\": [\"r1\",\"r2\",\"r3\"] }", "");
        assertEquals(Outcome.ACCEPTED, input("r3"));
        assertEquals(Outcome.ACCEPTED, input("decoy"));
        assertEquals(Outcome.ACCEPTED, input("r1"));
        assertEquals(Outcome.COMPLETED, input("r2"));
    }

    @Test
    void exactNeedsExactlyThatManyPlatesAtOnce() {
        puzzle("""
                [ { "id": "p1", "toggleable": true }, { "id": "p2", "toggleable": true },
                  { "id": "p3", "toggleable": true }, { "id": "p4", "toggleable": true } ]
                """, "{ \"type\": \"exact\", \"required\": 3 }", "");
        assertEquals(Outcome.ACCEPTED, input("p1"));
        assertEquals(Outcome.ACCEPTED, input("p2"));
        assertEquals(Outcome.DEACTIVATED, release("p2"));
        assertEquals(Outcome.ACCEPTED, input("p3"));
        assertEquals(Outcome.COMPLETED, input("p4"));
    }

    @Test
    void timedInputsExpireOutOfTheWindow() {
        puzzle("[\"s1\",\"s2\",\"s3\"]", "{ \"type\": \"timed\", \"window\": \"15s\" }", "");
        assertEquals(Outcome.ACCEPTED, input("s1"));
        kit.clock.advance(Duration.ofSeconds(10));
        assertEquals(Outcome.ACCEPTED, input("s2"));
        kit.clock.advance(Duration.ofSeconds(6));
        assertEquals(Outcome.ACCEPTED, input("s3"), "s1 fell out of the window, so this is not yet complete");
        assertEquals(Outcome.COMPLETED, input("s1"));
    }

    @Test
    void weightedSumsPower() {
        puzzle("""
                [ { "id": "small", "weight": 1 }, { "id": "large", "weight": 3 }, { "id": "medium", "weight": 2 } ]
                """, "{ \"type\": \"weighted\", \"threshold\": 4 }", "");
        assertEquals(Outcome.ACCEPTED, input("medium"));
        assertEquals(Outcome.ACCEPTED, input("small"));
        assertEquals(Outcome.COMPLETED, input("large"));
    }

    @Test
    void groupsNeedOneFromEachRoom() {
        puzzle("""
                [ { "id": "n1", "group": "north" }, { "id": "n2", "group": "north" },
                  { "id": "s1", "group": "south" }, { "id": "s2", "group": "south" } ]
                """, "{ \"type\": \"groups\" }", "");
        assertEquals(Outcome.ACCEPTED, input("n1"));
        assertEquals(Outcome.ACCEPTED, input("n2"));
        assertEquals(Outcome.COMPLETED, input("s2"));
    }

    @Test
    void stateMachinesFollowTheirTransitionsAndRunOnEnter() {
        kit.load("""
                {
                  "tagSchemas": [ { "id": "hyzion:stage2", "scope": "quest_session" } ],
                  "puzzles": [ {
                    "id": "hyzion:p",
                    "inputs": ["lever_a", "lever_b", "crank"],
                    "rule": { "type": "state_machine", "initial": "idle", "states": {
                      "idle":   { "transitions": { "lever_a": "primed" } },
                      "primed": { "transitions": { "lever_b": "idle", "crank": "open" },
                                  "onEnter": [ { "type": "mysticquests:tag.add", "tag": "hyzion:stage2" } ] },
                      "open":   { "terminal": true } } },
                    "outputs": [ { "type": "mysticquests:signal", "signal": "hyzion:mechanism.open" } ]
                  } ]
                }
                """);
        assertEquals(Outcome.IGNORED_NO_TRANSITION, input("crank"));
        assertEquals(Outcome.ACCEPTED, input("lever_a"));
        assertEquals(Outcome.COMPLETED, input("crank"));
        assertEquals(1, kit.signals.size());
        assertTrue(kit.signals.getFirst().contains("hyzion:mechanism.open"));
    }

    @Test
    void unsolvablePuzzlesFailTheReload() {
        String[] broken = {
                // n_of_m asking for more inputs than will be active
                "\"inputs\": [\"a\",\"b\"], \"rule\": { \"type\": \"n_of_m\", \"required\": 3 }",
                // weighted threshold nobody can reach
                "\"inputs\": [ { \"id\": \"a\", \"weight\": 1 } ], \"rule\": { \"type\": \"weighted\", \"threshold\": 5 }",
                // a machine with no reachable terminal state
                "\"inputs\": [\"a\"], \"rule\": { \"type\": \"state_machine\", \"initial\": \"s\", \"states\": { \"s\": { \"transitions\": { \"a\": \"s\" } }, \"end\": { \"terminal\": true } } }",
                // random selection cannot pick a sequence's named steps
                "\"inputs\": [\"a\",\"b\"], \"selection\": { \"active\": 1 }, \"rule\": { \"type\": \"sequence\", \"sequence\": [\"a\",\"b\"] }",
                // sequence naming an input that does not exist
                "\"inputs\": [\"a\"], \"rule\": { \"type\": \"sequence\", \"sequence\": [\"a\",\"ghost\"] }",
                // timed with no window
                "\"inputs\": [\"a\"], \"rule\": { \"type\": \"timed\" }",
                // selection picking more than the candidates
                "\"inputs\": [\"a\",\"b\"], \"selection\": { \"active\": 3 }, \"rule\": \"all\"",
                // an output that would fail at runtime
                "\"inputs\": [\"a\"], \"rule\": \"all\", \"outputs\": [ { \"type\": \"mysticquests:tag.add\", \"tag\": \"hyzion:undeclared\" } ]"};
        for (String body : broken) {
            DiagnosticReport report = kit.compile("{ \"puzzles\": [ { \"id\": \"hyzion:broken\", " + body + " } ] }");
            assertTrue(report.hasErrors(), body);
        }
        DiagnosticReport outputs = kit.compile("""
                { "puzzles": [ { "id": "hyzion:broken", "inputs": ["a"], "rule": "all",
                  "outputs": [ { "type": "mysticquests:puzzle.reset", "puzzle": "hyzion:no_such_puzzle" } ] } ] }
                """);
        assertTrue(outputs.has(DiagnosticCode.MISSING_REFERENCE), outputs.format());
    }
}
