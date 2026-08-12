package org.hyzionstudios.mysticquests.api;

import java.util.Collection;
import java.util.UUID;

/** Optional social-provider capability used for party quest sharing and party-scoped scripting. */
@FunctionalInterface
public interface QuestPartyProvider {
    /** Returns the actor and every current party member; an ungrouped actor may return only itself. */
    Collection<UUID> members(UUID actorId);
}
