package org.hyzionstudios.mysticquests.narrative.session;

import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.AudienceMode;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * Decides whose session a player's action belongs to, and builds the {@link ScopeContext} actions and
 * conditions run with.
 */
public final class AudienceResolver {
    private final Function<UUID, Optional<String>> partyOf;

    /** @param partyOf the stable party id of a player's current party, if they are in one */
    public AudienceResolver(Function<UUID, Optional<String>> partyOf) {
        this.partyOf = partyOf;
    }

    /** The session owner for {@code player} under {@code mode}; null for a party audience without a party. */
    @Nullable
    public SessionOwner owner(UUID player, AudienceMode mode) {
        Optional<String> party = partyOf.apply(player);
        return switch (mode) {
            case PLAYER -> SessionOwner.player(player);
            case PARTY -> party.map(SessionOwner::party).orElse(null);
            case AUTO -> party.map(SessionOwner::party).orElse(SessionOwner.player(player));
        };
    }

    public Optional<String> party(UUID player) {
        return partyOf.apply(player);
    }

    /** The owners whose sessions a player takes part in: themselves, then their party. */
    public List<SessionOwner> owners(UUID player) {
        List<SessionOwner> owners = new ArrayList<>(2);
        owners.add(SessionOwner.player(player));
        partyOf.apply(player).ifPresent(party -> owners.add(SessionOwner.party(party)));
        return owners;
    }

    public ScopeContext context(UUID player, QuestSession session, @Nullable String world) {
        return new ScopeContext(player, session.id(), session.storyKey(), partyOf.apply(player).orElse(null),
                world, null, null);
    }
}
