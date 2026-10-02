package org.hyzionstudios.mysticquests.narrative.media;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * §18.1 where a listener hears a sound from. Each is sent to one listener at a time, so two players
 * standing side by side can hear different lines from the same spot.
 */
public enum Spatial {
    /** In the listener's head: narration, UI, stingers. */
    TWO_D("2d"),
    /** At a fixed point, fading with the SoundEvent's own distance settings. */
    POSITION("position"),
    /** Following an entity, usually the speaking NPC. */
    ENTITY("entity");

    private final String authored;

    Spatial(String authored) {
        this.authored = authored;
    }

    public String authored() {
        return authored;
    }

    @Nullable
    public static Spatial parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String wanted = raw.trim().toLowerCase(Locale.ROOT);
        for (Spatial spatial : values()) {
            if (spatial.authored.equals(wanted)) {
                return spatial;
            }
        }
        return null;
    }
}
