package org.hyzionstudios.mysticquests.narrative.state;

import javax.annotation.Nullable;

/**
 * Where the state of a {@link ScopeOwner} lives.
 *
 * <p>Most scopes live in the {@link NarrativeStateStore}. Session scope lives inside the session
 * document, so the session and its state move together. The tag and variable services only see this
 * interface, so they do not need to know which is which.
 */
@FunctionalInterface
public interface StateHost {
    /**
     * The live state of {@code owner}, created empty on first use. Null only when the owner cannot
     * exist here, such as a session id that matches no open session.
     */
    @Nullable
    OwnerState state(ScopeOwner owner);
}
