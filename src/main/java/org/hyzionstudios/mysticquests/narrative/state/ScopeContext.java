package org.hyzionstudios.mysticquests.narrative.state;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Everything needed to turn a scope into a concrete {@link ScopeOwner} for one execution: who is
 * acting, in which session, quest, party and world.
 *
 * <p>Fields are null when the situation does not have them, for example an action fired by a
 * server schedule has no actor. Resolving a scope that needs a missing field is a reported failure,
 * never a fallback to some other owner.
 */
public record ScopeContext(
        @Nullable UUID actor,
        @Nullable String sessionId,
        @Nullable String questKey,
        @Nullable String partyId,
        @Nullable String world,
        @Nullable String accountId,
        @Nullable String seasonId) {

    public static ScopeContext player(UUID actor) {
        return new ScopeContext(actor, null, null, null, null, null, null);
    }

    public static ScopeContext none() {
        return new ScopeContext(null, null, null, null, null, null, null);
    }

    public ScopeContext withSession(@Nullable String sessionId, @Nullable String questKey) {
        return new ScopeContext(actor, sessionId, questKey, partyId, world, accountId, seasonId);
    }

    public ScopeContext withParty(@Nullable String partyId) {
        return new ScopeContext(actor, sessionId, questKey, partyId, world, accountId, seasonId);
    }

    public ScopeContext withWorld(@Nullable String world) {
        return new ScopeContext(actor, sessionId, questKey, partyId, world, accountId, seasonId);
    }
}
