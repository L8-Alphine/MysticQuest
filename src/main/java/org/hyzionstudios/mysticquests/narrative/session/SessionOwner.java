package org.hyzionstudios.mysticquests.narrative.session;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;
import java.util.UUID;

/**
 * Who a {@link QuestSession} belongs to: one player, or one party by its provider's stable id.
 *
 * <p>A party session belongs to the party, not to any member. Members joining and leaving change
 * who experiences it, not whose it is. That is what lets a party's story survive its leader
 * logging off.
 */
public record SessionOwner(Kind kind, String id) {
    public enum Kind { PLAYER, PARTY }

    public SessionOwner {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(id, "id");
        if (id.isBlank()) {
            throw new IllegalArgumentException("A session owner needs a non-blank id.");
        }
    }

    public static SessionOwner player(UUID playerId) {
        return new SessionOwner(Kind.PLAYER, playerId.toString());
    }

    public static SessionOwner party(String partyId) {
        return new SessionOwner(Kind.PARTY, partyId);
    }

    /** {@code player:<uuid>} or {@code party:<id>}; the persisted form. */
    public String key() {
        return kind.name().toLowerCase(Locale.ROOT) + ":" + id;
    }

    @Nullable
    public static SessionOwner parse(@Nullable String key) {
        if (key == null) {
            return null;
        }
        int colon = key.indexOf(':');
        if (colon <= 0 || colon == key.length() - 1) {
            return null;
        }
        try {
            Kind kind = Kind.valueOf(key.substring(0, colon).toUpperCase(Locale.ROOT));
            return new SessionOwner(kind, key.substring(colon + 1));
        } catch (IllegalArgumentException unknownKind) {
            return null;
        }
    }

    /** The player UUID of a player owner; null for a party. */
    @Nullable
    public UUID playerId() {
        if (kind != Kind.PLAYER) {
            return null;
        }
        try {
            return UUID.fromString(id);
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }

    @Override
    public String toString() {
        return key();
    }
}
