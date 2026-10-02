package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A named action type for the narrative runtime.
 *
 * <p>Handlers need not be idempotent themselves: the executor records every settled action in the
 * owning session's ledger and never re-runs it for the same transition. A handler must still report
 * honestly. Returning {@code SUCCESS} for something that did not happen would mark it done forever.
 */
public interface ActionHandler {
    ActionResult execute(ActionContext context, ObjectNode parameters);

    /**
     * Checks the authored parameters at load time, reporting against {@code path}. The default
     * accepts anything; built-ins override it so that an unknown tag in a puzzle output fails the
     * reload instead of failing the first player who solves the puzzle.
     */
    default void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
    }

    /**
     * Whether this action's effect lives outside the session (inventory, economy, commands, other
     * mods), so it must never be replayed after a checkpoint rollback. Authors can override it per
     * action with {@code "permanent": true|false}. Defaults to false: built-in narrative actions only
     * touch state that rollback restores.
     */
    default boolean external(ObjectNode parameters) {
        return false;
    }
}
