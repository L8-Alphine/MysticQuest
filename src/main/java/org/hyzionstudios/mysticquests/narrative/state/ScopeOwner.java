package org.hyzionstudios.mysticquests.narrative.state;

import java.util.Objects;
import java.util.UUID;

/**
 * One concrete owner of narrative state: a scope plus the id of the thing in it.
 *
 * @param ownerId a player UUID for {@code PLAYER}, {@code <player>|<quest>} for {@code QUEST}, a
 *         session id for {@code QUEST_SESSION}, a party id for {@code PARTY}, a world name for
 *         {@code WORLD}, and so on
 */
public record ScopeOwner(VariableScope scope, String ownerId) {
    public ScopeOwner {
        Objects.requireNonNull(scope, "scope");
        Objects.requireNonNull(ownerId, "ownerId");
        if (ownerId.isBlank()) {
            throw new IllegalArgumentException("A " + scope.id() + " owner needs a non-blank id.");
        }
    }

    public static ScopeOwner player(UUID playerId) {
        return new ScopeOwner(VariableScope.PLAYER, playerId.toString());
    }

    public static ScopeOwner quest(UUID playerId, String questKey) {
        return new ScopeOwner(VariableScope.QUEST, playerId + "|" + questKey);
    }

    public static ScopeOwner session(String sessionId) {
        return new ScopeOwner(VariableScope.QUEST_SESSION, sessionId);
    }

    /** The persisted document key; unique across scopes because the scope id comes first. */
    public String key() {
        return scope.id() + "/" + ownerId;
    }

    @Override
    public String toString() {
        return key();
    }
}
