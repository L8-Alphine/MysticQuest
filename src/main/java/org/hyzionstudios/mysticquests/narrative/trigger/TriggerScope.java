package org.hyzionstudios.mysticquests.narrative.trigger;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * At which level a logical trigger enable or disable applies (§5.2 of the 2.0 specification).
 *
 * <p>Resolution goes from most to least specific: story session, then player, then party, then
 * global. The first level holding an override decides. When none does, the volume is enabled.
 * Disabling a volume for one player therefore never affects another (§26.3), unless the scope is
 * explicitly global.
 */
public enum TriggerScope {
    GLOBAL,
    PARTY,
    PLAYER,
    /** The session the volume's story runs in; also accepted as {@code quest_instance}. */
    STORY_SESSION;

    @Nullable
    public static TriggerScope parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "global", "server" -> GLOBAL;
            case "party" -> PARTY;
            case "player" -> PLAYER;
            case "session", "story_session", "quest_session", "quest_instance" -> STORY_SESSION;
            default -> null;
        };
    }
}
