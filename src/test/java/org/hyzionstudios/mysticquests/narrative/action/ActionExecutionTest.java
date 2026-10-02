package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.4, §22 and §26.1: typed results, idempotent transitions, failure handling. */
final class ActionExecutionTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private NarrativeTestKit kit;
    private final AtomicInteger rewardsPaid = new AtomicInteger();
    private final List<String> calls = new ArrayList<>();
    private volatile ActionResult flakyResult = ActionResult.retryable("player offline");

    @BeforeEach
    void setUp() {
        kit = new NarrativeTestKit();
        ActionTypeRegistry actions = kit.runtime().actionTypes();
        actions.register(id("test:reward"), new ActionHandler() {
            @Override
            public ActionResult execute(ActionContext context, com.fasterxml.jackson.databind.node.ObjectNode parameters) {
                rewardsPaid.incrementAndGet();
                calls.add("reward");
                return ActionResult.success();
            }

            @Override
            public boolean external(com.fasterxml.jackson.databind.node.ObjectNode parameters) {
                return true;
            }
        });
        actions.register(id("test:flaky"), (context, parameters) -> {
            calls.add("flaky");
            return flakyResult;
        });
        actions.register(id("test:throws"), (context, parameters) -> {
            throw new IllegalStateException("boom");
        });
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:counter", "type": "integer", "scope": "quest_session", "default": 0 } ] }
                """);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private List<ActionDefinition> compile(String json) {
        DiagnosticReport report = new DiagnosticReport();
        List<ActionDefinition> actions = ActionCompiler.compile(parse(json), "actions", context(), report);
        assertTrue(report.isEmpty(), report.format());
        return actions;
    }

    private CompileContext context() {
        return new CompileContext(kit.runtime().content().schemas(), ScopeSupport.standard(true),
                kit.runtime().conditionTypes(), kit.runtime().actionTypes(), "test");
    }

    private QuestSession session() {
        return kit.runtime().sessions().open(SessionOwner.player(ALICE), "story", "1");
    }

    private ActionContext context(QuestSession session) {
        return new ActionContext(ScopeContext.player(ALICE).withSession(session.id(), session.storyKey()), "test");
    }

    @Test
    void aCompletedTransitionNeverRunsAgain() {
        List<ActionDefinition> actions = compile("""
                [ { "type": "test:reward" },
                  { "type": "mysticquests:variable.increment", "variable": "hyzion:counter" } ]
                """);
        QuestSession session = session();
        TransitionReport first = kit.runtime().executor().run("complete", actions, context(session), session);
        assertTrue(first.complete());
        TransitionReport second = kit.runtime().executor().run("complete", actions, context(session), session);
        assertTrue(second.alreadyComplete());
        assertEquals(1, rewardsPaid.get());
        assertEquals(new IntValue(1), kit.runtime().variables()
                .get(context(session).scope(), null, id("hyzion:counter")).orElseThrow());
    }

    /** A crash or an offline player between two outputs resumes at the failed one, without repeating earlier ones. */
    @Test
    void aFailedStepStopsTheChainAndResumesWithoutRepeats() {
        List<ActionDefinition> actions = compile("""
                [ { "type": "test:reward", "stepId": "pay" },
                  { "type": "test:flaky" },
                  { "type": "mysticquests:variable.increment", "variable": "hyzion:counter" } ]
                """);
        QuestSession session = session();
        TransitionReport failed = kit.runtime().executor().run("t", actions, context(session), session);
        assertFalse(failed.complete());
        assertEquals(ActionStatus.RETRYABLE_FAILURE, failed.failure().result().status());
        assertEquals(List.of("reward", "flaky"), calls, "the increment after the failure did not run");

        flakyResult = ActionResult.success();
        TransitionReport resumed = kit.runtime().executor().run("t", actions, context(session), session);
        assertTrue(resumed.complete());
        assertTrue(resumed.outcomes().get(0).alreadyApplied(), "the reward is not paid twice");
        assertEquals(1, rewardsPaid.get());
        assertEquals(List.of("reward", "flaky", "flaky"), calls);
    }

    @Test
    void aThrowingHandlerIsATerminalFailureNotACrash() {
        List<ActionDefinition> actions = compile("[ { \"type\": \"test:throws\" } ]");
        QuestSession session = session();
        TransitionReport report = kit.runtime().executor().run("t", actions, context(session), session);
        assertEquals(ActionStatus.TERMINAL_FAILURE, report.failure().result().status());
        assertTrue(kit.problems.stream().anyMatch(problem -> problem.contains("ACTION_FAILED")));
    }

    /**
     * Rolling back to a checkpoint replays the session state changes after it, but never an action
     * whose effect lives outside the session.
     */
    @Test
    void rollbackReplaysSessionStateButNeverExternalRewards() {
        List<ActionDefinition> actions = compile("""
                [ { "type": "test:reward" },
                  { "type": "mysticquests:variable.increment", "variable": "hyzion:counter" } ]
                """);
        QuestSession session = session();
        session.checkpoint("before", kit.clock.instant());
        kit.runtime().executor().run("t", actions, context(session), session);
        assertTrue(session.rollback("before", new ArrayList<>()));
        assertEquals(new IntValue(0), kit.runtime().variables()
                .get(context(session).scope(), null, id("hyzion:counter")).orElseThrow(), "state rolled back");

        TransitionReport replay = kit.runtime().executor().run("t", actions, context(session), session);
        assertTrue(replay.complete());
        assertEquals(1, rewardsPaid.get(), "the external reward is permanent in the ledger");
        assertEquals(new IntValue(1), kit.runtime().variables()
                .get(context(session).scope(), null, id("hyzion:counter")).orElseThrow(), "session state re-applied");
    }

    @Test
    void compilationRejectsUnknownTypesAndDuplicateKeys() {
        DiagnosticReport report = new DiagnosticReport();
        ActionCompiler.compile(parse("""
                [ { "type": "mymod:nope" },
                  { "type": "sendTitle", "title": "no bridge in this test" },
                  { "type": "test:reward", "stepId": "a" },
                  { "type": "test:reward", "stepId": "a" },
                  { "type": "mysticquests:tag.add", "tag": "hyzion:undeclared" },
                  { "type": "mysticquests:variable.set", "variable": "hyzion:counter", "value": "x" } ]
                """), "actions", context(), report);
        assertTrue(report.has(DiagnosticCode.UNKNOWN_ACTION));
        assertTrue(report.has(DiagnosticCode.DUPLICATE_ID));
        assertTrue(report.has(DiagnosticCode.UNKNOWN_TAG));
        assertTrue(report.has(DiagnosticCode.INVALID_VARIABLE_TYPE));
    }

    @Test
    void authorsCanOverrideWhetherAStepIsPermanent() {
        List<ActionDefinition> actions = compile("""
                [ { "type": "test:reward", "permanent": false },
                  { "type": "mysticquests:variable.increment", "variable": "hyzion:counter", "permanent": true } ]
                """);
        assertFalse(actions.get(0).permanent());
        assertTrue(actions.get(1).permanent());
        assertFalse(actions.get(1).parameters().has("permanent"), "the flag is metadata, not a parameter");
    }
}
