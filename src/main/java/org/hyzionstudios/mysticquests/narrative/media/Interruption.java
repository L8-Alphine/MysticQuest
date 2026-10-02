package org.hyzionstudios.mysticquests.narrative.media;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * §18.1 what happens when a line arrives on a channel that is still busy for a listener.
 *
 * <p>The engine (0.6.8) has no packet that stops a sound already playing, so every policy acts on
 * what starts next, never on audio already sent: an interrupting line drops the queue and replaces
 * the subtitle, while the earlier line plays out underneath. Authors who need a hard cut keep lines
 * short. DUCK_EXISTING is refused for the same reason; ducking is configured on the SoundEvent asset.
 */
public enum Interruption {
    /** Wait until the current line ends, then play. */
    QUEUE,
    /** Play now and drop anything queued. */
    INTERRUPT,
    /** Drop the new line while the channel is busy. */
    IGNORE_NEW,
    /** Play now alongside whatever is playing; the channel is not tracked. */
    MIX,
    /** Interrupt a line by the same speaker; queue behind anyone else. */
    REPLACE_SAME_SPEAKER;

    @Nullable
    public static Interruption parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return valueOf(raw.trim().toUpperCase(Locale.ROOT).replace('-', '_'));
        } catch (IllegalArgumentException unknown) {
            return null;
        }
    }
}
