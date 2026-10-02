package org.hyzionstudios.mysticquests.narrative.action;

/** How one action ended (§4.4 of the 2.0 specification). */
public enum ActionStatus {
    /** It did what it was asked. */
    SUCCESS,
    /** Nothing needed doing, or an optional capability is absent; the transition carries on. */
    SKIPPED,
    /** It failed for a reason that may clear (the player is offline, a world is not loaded). */
    RETRYABLE_FAILURE,
    /** It failed and will fail again until content or configuration changes. */
    TERMINAL_FAILURE;

    /** Whether the action counts as done for the transition ledger. */
    public boolean settled() {
        return this == SUCCESS || this == SKIPPED;
    }
}
