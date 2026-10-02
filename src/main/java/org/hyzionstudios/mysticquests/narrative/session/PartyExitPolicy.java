package org.hyzionstudios.mysticquests.narrative.session;

/**
 * What a member keeps of the party's sessions when they leave, or when the party disbands (§8.1:
 * "party join/leave/disband behavior must be explicitly defined and testable").
 */
public enum PartyExitPolicy {
    /**
     * The leaver gets a player-owned copy of every active party session, so their progress so far
     * continues solo. A copy is skipped when they already have their own active session for that
     * story. This is the default.
     */
    FORK,
    /** The leaver keeps nothing; the story stays with the party. */
    DETACH
}
