package org.hyzionstudios.mysticquests.api.trial;

/** How a trial run resolved. */
public enum TrialStatus {
    /** Every required objective was done within the trial's limits. */
    COMPLETED,
    /** The run broke a rule of the trial (time limit, deaths) or content failed it. */
    FAILED,
    /** The player left, cancelled, or the server stopped during the run. */
    ABANDONED,
    /** No run began: unknown trial, start conditions unmet, or MysticQuests stopping. */
    REFUSED
}
