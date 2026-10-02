package org.hyzionstudios.mysticquests.narrative.state;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * Who a narrative tag or variable belongs to (§4.2 of the 2.0 specification).
 *
 * <p>Unlike v1's {@code StateScope#parse}, which turned any unknown scope into {@code PLAYER}, an
 * unrecognised scope here is an error. A typo in a scope name used to give a value the wrong
 * owner. Now it is reported where it was written.
 */
public enum VariableScope {
    /** One player, across every quest. */
    PLAYER("player", true),
    /** One player's account, across characters; needs an identity provider. */
    ACCOUNT("account", true),
    /** One player within one quest — v1's quest variables. */
    QUEST("quest", true),
    /** One quest session, player- or party-owned; persisted inside the session document. */
    QUEST_SESSION("quest_session", true),
    /** One party, keyed by the party provider's stable id. */
    PARTY("party", true),
    /** One world on this server. */
    WORLD("world", true),
    /** This server process. */
    SERVER("server", true),
    /** The whole network; needs a shared state provider. */
    NETWORK("network", true),
    /** One season; needs a season provider. */
    SEASON("season", true),
    /** One player for as long as they stay online. Never written to disk. */
    TEMPORARY("temporary", false);

    private final String id;
    private final boolean persistent;

    VariableScope(String id, boolean persistent) {
        this.id = id;
        this.persistent = persistent;
    }

    public String id() {
        return id;
    }

    public boolean persistent() {
        return persistent;
    }

    /** Parses a scope name. Null when unknown — callers report it rather than guessing. */
    @Nullable
    public static VariableScope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
        for (VariableScope scope : values()) {
            if (scope.id.equals(normalized)) {
                return scope;
            }
        }
        return switch (normalized) {
            case "session", "questsession", "story_session" -> QUEST_SESSION;
            case "global" -> SERVER;
            case "temp" -> TEMPORARY;
            default -> null;
        };
    }
}
