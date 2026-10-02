package org.hyzionstudios.mysticquests.narrative.action;

/**
 * Remembers which transition steps have already been applied, so re-running a transition never
 * applies a step twice (§22: "avoid duplicate rewards with idempotency keys/checkpoints").
 *
 * <p>The session implements this and persists the keys with the rest of the session. A crash
 * between "reward given" and "transition finished" therefore resumes at the next step after
 * restart instead of paying the reward again.
 *
 * <h2>Permanent keys</h2>
 *
 * <p>Rolling a session back to a checkpoint also rolls back its ordinary keys, so the transitions
 * after the checkpoint re-apply the session state they changed. Some steps, though, act outside the
 * session: they give an item, pay money, or run a command. A rollback cannot undo those, so their
 * keys are recorded as <em>permanent</em>, survive rollback, and are never replayed.
 */
public interface TransitionLedger {
    boolean applied(String key);

    /** @param permanent keep this key through checkpoint rollback; see the class note */
    void record(String key, boolean permanent);
}
