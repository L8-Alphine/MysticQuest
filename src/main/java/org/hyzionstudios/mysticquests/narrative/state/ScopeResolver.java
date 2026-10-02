package org.hyzionstudios.mysticquests.narrative.state;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * Resolves a {@link VariableScope} to the concrete {@link ScopeOwner} for one execution.
 *
 * <p>Resolution either succeeds or explains why it cannot. A party-scoped write from a player who is
 * not in a party fails visibly. It is not redirected to the player, because that would quietly put
 * shared progress where only one member can see it.
 */
public final class ScopeResolver {
    /** The outcome of one resolution. Exactly one of the two fields is set. */
    public record Resolution(@Nullable ScopeOwner owner, @Nullable String problem) {
        static Resolution of(ScopeOwner owner) {
            return new Resolution(owner, null);
        }

        static Resolution fail(String problem) {
            return new Resolution(null, problem);
        }

        public boolean resolved() {
            return owner != null;
        }
    }

    private final ScopeSupport support;
    private final String serverId;
    private final String networkId;

    /**
     * @param serverId this server's stable id; also the owner of {@code SERVER} state, so it must not
     *         change between restarts or that state becomes unreachable
     */
    public ScopeResolver(ScopeSupport support, String serverId, String networkId) {
        this.support = Objects.requireNonNull(support, "support");
        this.serverId = requireId(serverId, "serverId");
        this.networkId = requireId(networkId, "networkId");
    }

    public ScopeSupport support() {
        return support;
    }

    public String serverId() {
        return serverId;
    }

    public Resolution resolve(VariableScope scope, ScopeContext context) {
        ScopeSupport.Status status = support.status(scope);
        if (status.level() == ScopeSupport.Level.UNSUPPORTED) {
            return Resolution.fail(scope.id() + " scope is unsupported: " + status.reason());
        }
        return switch (scope) {
            case PLAYER -> context.actor() == null
                    ? Resolution.fail("player scope needs an acting player")
                    : Resolution.of(ScopeOwner.player(context.actor()));
            case TEMPORARY -> context.actor() == null
                    ? Resolution.fail("temporary scope needs an acting player")
                    : Resolution.of(new ScopeOwner(VariableScope.TEMPORARY, context.actor().toString()));
            case QUEST -> context.actor() == null || isBlank(context.questKey())
                    ? Resolution.fail("quest scope needs an acting player inside a quest")
                    : Resolution.of(ScopeOwner.quest(context.actor(), context.questKey()));
            case QUEST_SESSION -> isBlank(context.sessionId())
                    ? Resolution.fail("quest_session scope needs an active quest session")
                    : Resolution.of(ScopeOwner.session(context.sessionId()));
            case PARTY -> isBlank(context.partyId())
                    ? Resolution.fail("party scope needs the acting player to be in a party")
                    : Resolution.of(new ScopeOwner(VariableScope.PARTY, context.partyId()));
            case WORLD -> isBlank(context.world())
                    ? Resolution.fail("world scope needs a world; the acting player is not in one")
                    : Resolution.of(new ScopeOwner(VariableScope.WORLD, context.world()));
            case SERVER -> Resolution.of(new ScopeOwner(VariableScope.SERVER, serverId));
            case NETWORK -> Resolution.of(new ScopeOwner(VariableScope.NETWORK, networkId));
            case ACCOUNT -> isBlank(context.accountId())
                    ? Resolution.fail("account scope needs an account id from the identity provider")
                    : Resolution.of(new ScopeOwner(VariableScope.ACCOUNT, context.accountId()));
            case SEASON -> isBlank(context.seasonId())
                    ? Resolution.fail("season scope needs an active season")
                    : Resolution.of(new ScopeOwner(VariableScope.SEASON, context.seasonId()));
        };
    }

    private static boolean isBlank(@Nullable String value) {
        return value == null || value.isBlank();
    }

    private static String requireId(String value, String name) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
