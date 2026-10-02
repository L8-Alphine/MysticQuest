package org.hyzionstudios.mysticquests.state;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The five ownership scopes tags and variables can live in.
 *
 * <p>Replaces the string comparisons that used to be spread across the state service, the quest
 * event dispatcher, and the storage layer, so a scope is validated once at the edge and then passed
 * around as an enum. {@link #parse} accepts the aliases that already appear in authored content.
 */
public enum StateScope {
    /** Owned by a player UUID. */
    PLAYER("player"),
    /** Server-wide state, always owned by {@link #GLOBAL_OWNER}. */
    GLOBAL("global"),
    /** Owned by an entity UUID — NPCs, mobs, and anything else with a UUID component. */
    ENTITY("entity"),
    /** Owned by a {@code world:x:y:z} block key. */
    BLOCK("block"),
    /** Owned by a {@code world:volumeId} trigger volume key. */
    VOLUME("volume");

    /** The single owner id used for every {@link #GLOBAL} entry. */
    public static final String GLOBAL_OWNER = "__global__";

    private final String id;

    StateScope(String id) {
        this.id = id;
    }

    /** The stable lower-case id used in content JSON and on disk. */
    public String id() {
        return id;
    }

    /**
     * Parses a scope from authored content, falling back to {@link #PLAYER} for anything
     * unrecognised so a typo in a quest package degrades to player scope rather than failing load.
     */
    public static StateScope parse(@Nullable String scope) {
        if (scope == null || scope.isBlank()) {
            return PLAYER;
        }
        return switch (scope.trim().toLowerCase(Locale.ROOT)) {
            case "global", "server" -> GLOBAL;
            case "entity", "npc" -> ENTITY;
            case "block" -> BLOCK;
            case "volume", "trigger", "triggervolume" -> VOLUME;
            default -> PLAYER;
        };
    }

    /** Parses a scope id written by this mod, returning null when it is not one of ours. */
    @Nullable
    public static StateScope fromId(@Nullable String id) {
        if (id == null) {
            return null;
        }
        for (StateScope scope : values()) {
            if (scope.id.equals(id)) {
                return scope;
            }
        }
        return null;
    }

    /**
     * Normalises an owner id for this scope. {@link #GLOBAL} collapses every owner onto
     * {@link #GLOBAL_OWNER}; other scopes reject blank owners by collapsing them the same way, which
     * keeps a missing target from silently writing under an empty key.
     */
    public String normalizeOwner(@Nullable String owner) {
        if (this == GLOBAL || owner == null || owner.isBlank()) {
            return GLOBAL_OWNER;
        }
        return owner;
    }
}
