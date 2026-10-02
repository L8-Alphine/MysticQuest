package org.hyzionstudios.mysticquests.narrative.session;

/** Where a session is in its life. Only {@link #ACTIVE} sessions receive new input. */
public enum SessionStatus {
    ACTIVE,
    COMPLETED,
    ABANDONED,
    /** A party session closed by disband, after its members received their own copies. */
    ARCHIVED
}
