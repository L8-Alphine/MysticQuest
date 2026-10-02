package org.hyzionstudios.mysticquests.narrative.media;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * §13.1 logical media types. The kind picks the engine sound category (so players' volume sliders
 * apply) and the default channel and interruption policy.
 */
public enum MediaKind {
    /** NPC dialogue, narrator lines, player-character lines. Queued on the "voice" channel. */
    VOICE("voice", Interruption.QUEUE),
    /** Doors, locks, magic, mechanisms, puzzle confirmation. */
    SFX(null, Interruption.MIX),
    /** Wind, cave beds, machinery. Long beds belong in a MusicContainer played with music.set. */
    AMBIENT(null, Interruption.MIX),
    /** Exploration, story, boss and combat themes; a MusicContainer, switched with music.set. */
    MUSIC(null, Interruption.MIX),
    /** Discovery, reveal, victory, failure. */
    STINGER(null, Interruption.MIX),
    /** Quest accepted, objective updated, warning. */
    UI(null, Interruption.MIX),
    /** Timeline-specific stems and impacts. */
    CINEMATIC(null, Interruption.MIX);

    @Nullable
    private final String defaultChannel;
    private final Interruption defaultInterruption;

    MediaKind(@Nullable String defaultChannel, Interruption defaultInterruption) {
        this.defaultChannel = defaultChannel;
        this.defaultInterruption = defaultInterruption;
    }

    @Nullable
    public String defaultChannel() {
        return defaultChannel;
    }

    public Interruption defaultInterruption() {
        return defaultInterruption;
    }

    @Nullable
    public static MediaKind parse(@Nullable String raw) {
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
