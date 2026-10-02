package org.hyzionstudios.mysticquests.api;

import java.util.Collection;
import java.util.Optional;
import java.util.UUID;

/**
 * Optional social-provider capability used for party quest sharing and party-scoped scripting.
 *
 * <p>A provider registered with MysticRPG's service registry is found automatically. {@link #members}
 * is enough for v1 party features ({@code party} events, shared objectives, {@code inParty}). Party
 * story sessions and {@code party}-scoped narrative state also need {@link #partyId}: a stable id
 * that stays the same while members come and go, and survives restarts. A provider that also
 * reports members leaving and parties disbanding through
 * {@link MysticQuestsApi#partyMemberLeft} and {@link MysticQuestsApi#partyDisbanded} lets the
 * server's party exit policy hand a leaving player their own copy of the story.
 */
@FunctionalInterface
public interface QuestPartyProvider {
    /** Returns the actor and every current party member; an ungrouped actor may return only itself. */
    Collection<UUID> members(UUID actorId);

    /** The stable id of the actor's current party; empty when they are in none or ids are not supported. */
    default Optional<String> partyId(UUID actorId) {
        return Optional.empty();
    }
}
