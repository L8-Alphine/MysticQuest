package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Objects;

/**
 * One compiled action: its type, its authored parameters, and the key that identifies it inside its
 * transition.
 *
 * @param key stable within the action list: the authored {@code stepId} when given, otherwise the
 *         position. The transition ledger records {@code <transition>#<key>}, so a retried
 *         transition skips actions that already ran. Give rewards an explicit {@code stepId} if a
 *         release might reorder the list while a transition is half-applied.
 * @param permanent its effect lives outside the session (an item, money, a command), so its ledger
 *         key survives checkpoint rollback and it is never replayed; see {@link TransitionLedger}
 * @param path where it was authored, for diagnostics
 */
public record ActionDefinition(NamespacedId type, ObjectNode parameters, String key, boolean permanent, String path) {
    public ActionDefinition {
        Objects.requireNonNull(type, "type");
        parameters = parameters.deepCopy();
        Objects.requireNonNull(key, "key");
        path = path == null ? "" : path;
    }
}
