package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Objects;

/**
 * Who a line belongs to (§17): shown before subtitles, and used by
 * {@link Interruption#REPLACE_SAME_SPEAKER}.
 *
 * @param name literal display name; {@code nameKey} is a translation key and wins when both are set
 */
public record Speaker(NamespacedId id, Type type, @Nullable String name, @Nullable String nameKey) {
    public enum Type {
        NPC, NARRATOR, SYSTEM, PLAYER, UNKNOWN, CUSTOM;

        @Nullable
        public static Type parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            try {
                return valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    public Speaker {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
    }
}
