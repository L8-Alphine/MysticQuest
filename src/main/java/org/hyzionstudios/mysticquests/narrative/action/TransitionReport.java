package org.hyzionstudios.mysticquests.narrative.action;

import javax.annotation.Nullable;
import java.util.List;

/**
 * What happened when a transition ran.
 *
 * @param complete every action is settled, now or on an earlier run, so the transition is recorded
 *         as done
 * @param alreadyComplete the transition had completed before this run, so nothing executed
 * @param outcomes one entry per action, in order; actions after a failure appear as not run
 */
public record TransitionReport(String key, boolean complete, boolean alreadyComplete, List<Outcome> outcomes) {
    public TransitionReport {
        outcomes = List.copyOf(outcomes);
    }

    /**
     * @param result null when the action did not execute on this run
     * @param alreadyApplied it settled on an earlier run and was skipped
     * @param nanos how long it took, for slow-action diagnostics (§28)
     */
    public record Outcome(ActionDefinition action, @Nullable ActionResult result, boolean alreadyApplied, long nanos) {
    }

    /** The first failure, if any — the reason the transition is incomplete. */
    @Nullable
    public Outcome failure() {
        for (Outcome outcome : outcomes) {
            if (outcome.result() != null && !outcome.result().status().settled()) {
                return outcome;
            }
        }
        return null;
    }
}
