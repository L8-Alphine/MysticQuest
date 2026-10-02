package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A named condition type for the narrative runtime.
 *
 * <p>Handlers run on whichever thread evaluates the condition, usually a world thread mid-tick, so
 * they must be cheap and must not block. A handler that throws is reported, and the condition reads
 * as false: denying on failure keeps a broken integration from opening content it was meant to gate.
 */
public interface ConditionHandler {
    boolean test(ScopeContext context, ObjectNode parameters);

    /**
     * Checks the authored parameters at load time. Report problems to {@code report} against
     * {@code path}; the default accepts anything.
     */
    default void validate(ObjectNode parameters, String path, DiagnosticReport report) {
    }
}
