package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Counter;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Gauge;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Snapshot;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.trigger.QuestTriggerService.Routed;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerEvent;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §28 limits and §29 counters: what each limit does when reached, and what the metrics record. */
final class NarrativeLimitsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final String E1 = "00000000-0000-4000-8000-0000000000e1";
    private static final String E2 = "00000000-0000-4000-8000-0000000000e2";
    private static final String E3 = "00000000-0000-4000-8000-0000000000e3";

    private NarrativeTestKit kit;

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private static UUID player(int index) {
        return new UUID(0x4000L, index + 1L);
    }

    private Routed walk(UUID player, String volume) {
        return kit.runtime().triggers().handle(new TriggerEvent(volume, "ENTER", player, player, List.of()), "test");
    }

    /** One single-input puzzle per entry, each claiming its own entity when solved. */
    private static String claimingPuzzles(String... entities) {
        StringBuilder puzzles = new StringBuilder();
        for (int index = 0; index < entities.length; index++) {
            puzzles.append(index == 0 ? "" : ",").append("""
                    { "id": "test:p%d", "story": "test:story%d", "inputs": [ { "id": "go", "volume": "test:v%d" } ], "rule": "all",
                      "outputs": [ { "type": "mysticquests:entity.claim", "entity": "uuid:%s" } ] }
                    """.formatted(index, index, index, entities[index]));
        }
        return "{ \"puzzles\": [ " + puzzles + " ] }";
    }

    @Test
    void storySessionsOverTheLimitStillOpenAndAreReportedOnce() {
        kit = new NarrativeTestKit(new NarrativeLimits(3, 100, 64));
        kit.load("""
                { "tagSchemas": [ { "id": "test:done", "scope": "quest_session" } ],
                  "puzzles": [ { "id": "test:p0", "story": "test:story0", "inputs": [ { "id": "go", "volume": "test:v0" } ], "rule": "all",
                                 "outputs": [ { "type": "mysticquests:tag.add", "tag": "test:done" } ] } ] }
                """);
        for (int index = 0; index < 5; index++) {
            assertEquals(Outcome.COMPLETED, walk(player(index), "test:v0").puzzleInputs().getFirst().outcome(),
                    "a story never refuses to open");
        }
        assertEquals(5, kit.runtime().sessions().loaded().size());
        assertEquals(1, kit.problems.stream().filter(problem -> problem.contains("over the limit of 3")).count(), kit.problems.toString());
        assertEquals(1, kit.runtime().metrics().count(Counter.LIMITS_REACHED));
        Gauge sessions = kit.runtime().metricsSnapshot().gauges().getFirst();
        assertTrue(sessions.over(), sessions.toString());
    }

    @Test
    void storyEntitiesOverTheLimitWaitAndClaimOnceOneIsReleased() {
        kit = new NarrativeTestKit(new NarrativeLimits(100, 2, 64));
        kit.load(claimingPuzzles(E1, E2, E3));
        walk(ALICE, "test:v0");
        walk(ALICE, "test:v1");
        Routed third = walk(ALICE, "test:v2");
        assertFalse(third.puzzleInputs().getFirst().transition().complete(), "the claim over the limit fails, retryably");
        assertEquals(2, kit.runtime().storyEntities().claims().size());
        assertTrue(kit.runtime().storyEntities().admits(new EntityRefValue("uuid", E1)), "a claimed entity may always move");
        assertEquals(1, kit.runtime().metrics().count(Counter.LIMITS_REACHED));

        kit.runtime().storyEntities().release(new EntityRefValue("uuid", E1));
        walk(ALICE, "test:v2");
        assertTrue(kit.runtime().storyEntities().claims().stream().anyMatch(claim -> claim.key().equals("uuid:" + E3)),
                "the waiting claim lands once there is room");
    }

    @Test
    void puzzlesWithMoreInputsThanTheLimitLoadWithAWarning() {
        kit = new NarrativeTestKit(new NarrativeLimits(100, 100, 2));
        DiagnosticReport report = kit.load("""
                { "puzzles": [ { "id": "test:big", "story": "test:story", "rule": "all", "inputs": [
                    { "id": "a", "volume": "test:a" }, { "id": "b", "volume": "test:b" }, { "id": "c", "volume": "test:c" } ] } ] }
                """);
        assertTrue(report.has(DiagnosticCode.LIMIT_EXCEEDED), report.format());
        assertFalse(report.hasErrors());
    }

    @Test
    void failuresAndSlowActionsAreCountedAndTimedByType() {
        kit = new NarrativeTestKit();
        kit.runtime().actionTypes().register(id("test:slow"), (context, parameters) -> {
            try {
                Thread.sleep(8);
            } catch (InterruptedException interrupted) {
                Thread.currentThread().interrupt();
            }
            return ActionResult.success();
        });
        kit.runtime().actionTypes().register(id("test:broken"), (context, parameters) -> ActionResult.terminal("broken on purpose"));
        kit.load("""
                { "puzzles": [
                    { "id": "test:slow", "story": "test:a", "inputs": [ { "id": "go", "volume": "test:slow" } ], "rule": "all",
                      "outputs": [ { "type": "test:slow" } ] },
                    { "id": "test:broken", "story": "test:b", "inputs": [ { "id": "go", "volume": "test:broken" } ], "rule": "all",
                      "outputs": [ { "type": "test:broken" } ] } ] }
                """);
        walk(ALICE, "test:slow");
        walk(ALICE, "test:broken");

        Snapshot snapshot = kit.runtime().metricsSnapshot();
        assertEquals(1, snapshot.count(Counter.TRANSITIONS_COMPLETED));
        assertEquals(1, snapshot.count(Counter.TRANSITIONS_FAILED));
        assertEquals(1, snapshot.count(Counter.ACTIONS_FAILED));
        assertTrue(snapshot.count(Counter.SLOW_OPERATIONS) >= 1);
        assertEquals("action:test:slow", snapshot.slowest(10).stream()
                .filter(timing -> timing.operation().startsWith("action:")).findFirst().orElseThrow().operation(),
                "actions are timed by type, so the slow one is named: " + snapshot.timings());
        assertTrue(kit.problems.stream().anyMatch(problem -> problem.startsWith("slow action")), kit.problems.toString());
        assertEquals(2, snapshot.count(Counter.TRIGGER_EVENTS));
        assertTrue(snapshot.timings().stream().anyMatch(timing -> timing.operation().equals("trigger.event")));
        assertTrue(snapshot.timings().stream().anyMatch(timing -> timing.operation().equals("content.compile")));

        kit.runtime().flush();
        kit.runtime().metrics().reset();
        assertEquals(0, kit.runtime().metrics().count(Counter.TRIGGER_EVENTS), "reset starts from zero");
    }
}
