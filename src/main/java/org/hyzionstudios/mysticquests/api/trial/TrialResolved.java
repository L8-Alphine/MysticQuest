package org.hyzionstudios.mysticquests.api.trial;

/**
 * Posted on the MysticQuests event bus once per trial run when it resolves, after the result is
 * saved. Subscribe with {@code MysticQuestsApi.subscribe(TrialResolved.class, …)}. A trial that was
 * refused before it began is not posted.
 */
public record TrialResolved(TrialResult result) {
}
