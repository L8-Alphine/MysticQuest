package org.hyzionstudios.mysticquests.service;

/**
 * Typed projection of one objective for UI surfaces. Replaces the previous
 * {@code "name: 3/5"} string, which the journal had to regex-parse back apart.
 *
 * <p>UI must not re-derive state from {@link #displayName()}; every field a surface needs is
 * carried explicitly (UI Design Bible 8.15, "typed semantics").
 */
public record ObjectiveView(
        String objectiveId,
        String displayName,
        int current,
        int target,
        State state) {

    public enum State {
        /** Counting up and currently actionable. */
        ACTIVE,
        /** Target reached. */
        COMPLETE
    }

    public static ObjectiveView of(String objectiveId, String displayName, int current, int target) {
        int safeTarget = Math.max(1, target);
        int safeCurrent = Math.max(0, Math.min(current, safeTarget));
        return new ObjectiveView(
                objectiveId,
                displayName,
                safeCurrent,
                safeTarget,
                safeCurrent >= safeTarget ? State.COMPLETE : State.ACTIVE);
    }

    /** Synthetic single-line entry for surfaces that show completed or not-yet-started quests. */
    public static ObjectiveView note(String displayName) {
        return new ObjectiveView("", displayName, 0, 1, State.ACTIVE);
    }

    public boolean complete() {
        return state == State.COMPLETE;
    }

    /** {@code "3 / 5"}, or empty for synthetic notes that carry no counter. */
    public String progressLabel() {
        return objectiveId.isEmpty() ? "" : current + " / " + target;
    }

    /** Single-line form for chat and other plain-text surfaces. */
    public String line() {
        return objectiveId.isEmpty() ? displayName : displayName + ": " + current + "/" + target;
    }
}
