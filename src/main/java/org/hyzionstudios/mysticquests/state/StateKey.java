package org.hyzionstudios.mysticquests.state;

/**
 * Identifies one state owner: a scope plus the owner id within it.
 *
 * <p>Used as the unit of dirty tracking. When anything about an owner changes, that owner's key is
 * marked dirty and its whole (small) entry is rewritten on the next flush — rather than the previous
 * behaviour of rewriting every owner on the server for a single tag change.
 */
public record StateKey(StateScope scope, String owner) {
    public static StateKey of(StateScope scope, String owner) {
        return new StateKey(scope, scope.normalizeOwner(owner));
    }

    public static StateKey global() {
        return new StateKey(StateScope.GLOBAL, StateScope.GLOBAL_OWNER);
    }

    @Override
    public String toString() {
        return scope.id() + ":" + owner;
    }
}
