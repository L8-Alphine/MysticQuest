package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;

/**
 * One narrative media resource (§13, §15): a voice line, sound effect, stinger, ambience, UI sound or
 * music track, addressed by stable id rather than by engine sound name.
 *
 * <pre>
 *   { "id": "hyzion:old_man.warning_001", "kind": "voice", "speaker": "hyzion:old_man",
 *     "sounds": { "en-US": "SFX_OldMan_Warning_001_EN", "fr-FR": "SFX_OldMan_Warning_001_FR" },
 *     "subtitleKey": "dialogue.old_man.warning_001", "duration": 4.2 }
 * </pre>
 *
 * @param sound the SoundEvent asset id played when no locale-specific recording fits; null when only
 *         {@code localized} recordings exist
 * @param localized SoundEvent asset ids per locale (keys lower-cased, for example {@code en-us})
 * @param music the MusicContainer asset id, for {@link MediaKind#MUSIC} only
 * @param subtitle literal subtitle text; {@code subtitleKey} is preferred because it follows the
 *         reader's language rather than the voice language
 * @param durationMillis how long the line lasts; 0 when unknown. A queued channel needs it to know
 *         when the next line may start, because the engine reports no playback progress
 * @param channel lines on one channel interrupt or queue behind each other per listener; null means
 *         the asset plays freely over anything else
 */
public record MediaAsset(
        NamespacedId id,
        String packageId,
        MediaKind kind,
        @Nullable String sound,
        Map<String, String> localized,
        @Nullable String music,
        @Nullable NamespacedId speaker,
        @Nullable String subtitle,
        @Nullable String subtitleKey,
        long durationMillis,
        float volume,
        float pitch,
        Spatial spatial,
        @Nullable String channel,
        Interruption interruption) {

    public MediaAsset {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(kind, "kind");
        localized = Map.copyOf(localized);
        Objects.requireNonNull(spatial, "spatial");
        Objects.requireNonNull(interruption, "interruption");
    }

    /** Which recording a listener hears, and whether it is a fallback worth diagnosing. */
    public record Recording(String sound, String locale, boolean fellBack) {
    }

    public boolean hasSubtitle() {
        return (subtitleKey != null && !subtitleKey.isBlank()) || (subtitle != null && !subtitle.isBlank());
    }

    /**
     * Picks the recording for a listener's language: the exact locale, then any recording in the
     * same language, then the server's fallback locale, then the unlocalised {@code sound}.
     *
     * @return null when nothing fits, which the compiler prevents for content it accepted
     */
    @Nullable
    public Recording recordingFor(@Nullable String wanted, String fallbackLocale) {
        String want = normalise(wanted);
        if (!want.isEmpty()) {
            String exact = localized.get(want);
            if (exact != null) {
                return new Recording(exact, want, false);
            }
            String language = language(want);
            for (Map.Entry<String, String> entry : localized.entrySet()) {
                if (language(entry.getKey()).equals(language)) {
                    return new Recording(entry.getValue(), entry.getKey(), false);
                }
            }
        }
        String fallback = normalise(fallbackLocale);
        String fallbackSound = localized.get(fallback);
        if (fallbackSound != null) {
            return new Recording(fallbackSound, fallback, !want.isEmpty());
        }
        if (sound != null) {
            return new Recording(sound, "", !localized.isEmpty() && !want.isEmpty());
        }
        return null;
    }

    public static String normalise(@Nullable String locale) {
        return locale == null ? "" : locale.trim().replace('_', '-').toLowerCase(Locale.ROOT);
    }

    private static String language(String locale) {
        int dash = locale.indexOf('-');
        return dash < 0 ? locale : locale.substring(0, dash);
    }
}
