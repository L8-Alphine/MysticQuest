package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Counter;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Runs an ordered list of actions as one idempotent transition.
 *
 * <p>The contract (§4.4, §22):
 * <ul>
 *   <li>Each action is keyed {@code <transition>#<action key>}. A settled action (success or skip) is
 *       recorded in the {@link TransitionLedger} as soon as it settles, and is never run again for
 *       the same transition.</li>
 *   <li>Execution stops at the first failure. Later actions do not run, because outputs are authored
 *       in order and a later one may assume an earlier one happened. The transition stays
 *       incomplete and can be re-run; already-settled actions are skipped.</li>
 *   <li>When every action has settled, the transition key itself is recorded, and a later run is a
 *       no-op. This is what makes "completion fires configured outputs once" hold across retries,
 *       reconnects and restarts.</li>
 * </ul>
 *
 * <p>A handler that throws is a terminal failure for that action, reported with its cause. It never
 * propagates into the caller, which is usually a world tick.
 */
public final class ActionExecutor {
    private final ActionTypeRegistry types;
    private final Consumer<String> problems;
    private final NarrativeMetrics metrics;

    /** @param metrics times every action by type; actions slower than {@link NarrativeMetrics#SLOW_NANOS} are reported */
    public ActionExecutor(ActionTypeRegistry types, Consumer<String> problems, NarrativeMetrics metrics) {
        this.types = types;
        this.problems = problems;
        this.metrics = metrics;
    }

    public TransitionReport run(String transitionKey, List<ActionDefinition> actions, ActionContext context, TransitionLedger ledger) {
        if (ledger.applied(transitionKey)) {
            return new TransitionReport(transitionKey, true, true, List.of());
        }
        List<TransitionReport.Outcome> outcomes = new ArrayList<>(actions.size());
        boolean failed = false;
        for (ActionDefinition action : actions) {
            if (failed) {
                outcomes.add(new TransitionReport.Outcome(action, null, false, 0L));
                continue;
            }
            String stepKey = transitionKey + "#" + action.key();
            if (ledger.applied(stepKey)) {
                outcomes.add(new TransitionReport.Outcome(action, null, true, 0L));
                continue;
            }
            long started = System.nanoTime();
            ActionResult result = execute(action, context);
            long elapsed = System.nanoTime() - started;
            outcomes.add(new TransitionReport.Outcome(action, result, false, elapsed));
            if (metrics.time("action:" + action.type(), elapsed)) {
                problems.accept("slow action " + action.path() + " (" + action.type() + ") took "
                        + Duration.ofNanos(elapsed).toMillis() + "ms");
            }
            if (result.status().settled()) {
                ledger.record(stepKey, action.permanent());
            } else {
                failed = true;
                metrics.increment(Counter.ACTIONS_FAILED);
                problems.accept(DiagnosticCode.ACTION_FAILED + " " + action.path() + " (" + action.type() + ") "
                        + result.status() + ": " + result.message() + " [transition " + transitionKey + "]");
            }
        }
        if (!failed) {
            ledger.record(transitionKey, false);
        }
        if (!actions.isEmpty()) {
            metrics.increment(failed ? Counter.TRANSITIONS_FAILED : Counter.TRANSITIONS_COMPLETED);
        }
        return new TransitionReport(transitionKey, !failed, false, outcomes);
    }

    private ActionResult execute(ActionDefinition action, ActionContext context) {
        ActionHandler handler = types.handler(action.type());
        if (handler == null) {
            metrics.increment(Counter.MISSING_REFERENCES);
            return ActionResult.terminal("action type " + action.type() + " is no longer registered");
        }
        try {
            ActionResult result = handler.execute(context, action.parameters());
            return result == null ? ActionResult.terminal(action.type() + " returned no result") : result;
        } catch (RuntimeException failure) {
            return ActionResult.terminal(action.type() + " threw " + failure, failure);
        }
    }
}
