package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Reads the {@code speakers} and {@code media} sections. Asset ids are checked against the engine
 * when it can tell; a missing asset is a warning because the runtime falls back to subtitles.
 */
public final class MediaCompiler {
    private MediaCompiler() {
    }

    public static void speaker(JsonNode entry, String path, DiagnosticReport report, Map<NamespacedId, Speaker> into) {
        NamespacedId id = ContentParams.id(entry, "id", path, report);
        if (id == null) {
            return;
        }
        String speakerPath = path + "<" + id + ">";
        Speaker.Type type = entry.hasNonNull("type") ? Speaker.Type.parse(entry.get("type").asText()) : Speaker.Type.NPC;
        if (type == null) {
            report.error(DiagnosticCode.INVALID_PARAMETER, speakerPath, "unknown speaker type '" + entry.get("type").asText() + "'",
                    "use npc, narrator, system, player, unknown or custom");
            return;
        }
        Speaker speaker = new Speaker(id, type, text(entry, "name"), text(entry, "nameKey"));
        if (into.putIfAbsent(id, speaker) != null) {
            report.error(DiagnosticCode.DUPLICATE_ID, speakerPath, "speaker " + id + " is defined twice");
        }
    }

    public static void asset(JsonNode entry, String path, String packageId, MediaCatalog catalog, String fallbackLocale,
                             DiagnosticReport report, Map<NamespacedId, MediaAsset> into) {
        NamespacedId id = ContentParams.id(entry, "id", path, report);
        if (id == null) {
            return;
        }
        String assetPath = path + "<" + id + ">";
        MediaKind kind = entry.hasNonNull("kind") ? MediaKind.parse(entry.get("kind").asText()) : MediaKind.SFX;
        if (kind == null) {
            report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "unknown media kind '" + entry.get("kind").asText() + "'",
                    "use voice, sfx, ambient, music, stinger, ui or cinematic");
            return;
        }
        int errors = report.errors().size();

        String sound = text(entry, "sound");
        Map<String, String> localized = new LinkedHashMap<>();
        JsonNode sounds = entry.get("sounds");
        if (sounds != null && !sounds.isNull()) {
            if (!sounds.isObject()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "\"sounds\" must map a locale to a SoundEvent id");
            } else {
                sounds.properties().forEach(locale -> {
                    String key = MediaAsset.normalise(locale.getKey());
                    String value = locale.getValue().asText("");
                    if (key.isEmpty() || value.isBlank()) {
                        report.error(DiagnosticCode.INVALID_PARAMETER, assetPath + ".sounds",
                                "locale '" + locale.getKey() + "' needs a SoundEvent id");
                    } else if (localized.putIfAbsent(key, value.trim()) != null) {
                        report.error(DiagnosticCode.DUPLICATE_ID, assetPath + ".sounds", "locale " + key + " is listed twice");
                    }
                });
            }
        }
        String music = text(entry, "music");
        if (kind == MediaKind.MUSIC) {
            if (music == null || sound != null || !localized.isEmpty()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "music names a MusicContainer in \"music\" and nothing else",
                        "music is switched per listener with mysticquests:music.set; one-shot stingers use kind stinger");
            } else if (Boolean.FALSE.equals(catalog.musicExists(music))) {
                report.warning(DiagnosticCode.MISSING_ASSET, assetPath, "MusicContainer '" + music + "' is not loaded on this server");
            }
        } else {
            if (music != null) {
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "only kind music takes \"music\"");
            }
            if (sound == null && localized.isEmpty()) {
                report.error(DiagnosticCode.MISSING_ASSET, assetPath, "needs a SoundEvent id in \"sound\" or per locale in \"sounds\"");
            } else if (sound == null && !localized.containsKey(MediaAsset.normalise(fallbackLocale))) {
                report.error(DiagnosticCode.MISSING_ASSET, assetPath, "has no recording for the fallback locale "
                        + fallbackLocale + " and no default \"sound\"",
                        "add \"" + fallbackLocale + "\" to \"sounds\" or a default \"sound\", or players in other languages hear nothing");
            }
            if (sound != null) {
                checkSound(catalog, sound, assetPath, report);
            }
            localized.values().forEach(value -> checkSound(catalog, value, assetPath, report));
        }

        Spatial spatial = Spatial.TWO_D;
        if (entry.hasNonNull("spatial")) {
            spatial = Spatial.parse(entry.get("spatial").asText());
            if (spatial == null) {
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "unknown spatial mode '" + entry.get("spatial").asText() + "'",
                        "use 2d, position or entity; for a block use position at its centre, and for an area use "
                                + "position, which already fades with the SoundEvent's MaxDistance");
                spatial = Spatial.TWO_D;
            }
        }

        Interruption interruption = kind.defaultInterruption();
        if (entry.hasNonNull("interruption")) {
            String raw = entry.get("interruption").asText();
            interruption = Interruption.parse(raw);
            if (interruption == null) {
                boolean duck = raw.trim().toLowerCase(java.util.Locale.ROOT).replace('-', '_').equals("duck_existing");
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "unsupported interruption '" + raw + "'",
                        duck ? "the server cannot change the volume of a sound already playing; configure ducking on the SoundEvent asset"
                                : "use queue, interrupt, ignore_new, mix or replace_same_speaker");
                interruption = kind.defaultInterruption();
            }
        }
        String channel = entry.has("channel") ? text(entry, "channel") : kind.defaultChannel();
        if (channel != null && channel.equalsIgnoreCase("none")) {
            channel = null;
        }

        long durationMillis = 0;
        if (entry.hasNonNull("duration")) {
            double seconds = entry.get("duration").asDouble(-1);
            if (!entry.get("duration").isNumber() || seconds <= 0) {
                report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "\"duration\" is a number of seconds above zero");
            } else {
                durationMillis = Math.round(seconds * 1000);
            }
        }
        if (channel != null && durationMillis == 0 && kind != MediaKind.MUSIC
                && (interruption == Interruption.QUEUE || interruption == Interruption.REPLACE_SAME_SPEAKER)) {
            report.warning(DiagnosticCode.INVALID_PARAMETER, assetPath, "queues on channel '" + channel
                    + "' but has no \"duration\", so the next line starts at once and the two overlap");
        }
        float volume = (float) entry.path("volume").asDouble(1.0);
        float pitch = (float) entry.path("pitch").asDouble(1.0);
        if (volume <= 0 || pitch <= 0) {
            report.error(DiagnosticCode.INVALID_PARAMETER, assetPath, "\"volume\" and \"pitch\" must be above zero");
        }
        NamespacedId speaker = null;
        if (entry.hasNonNull("speaker")) {
            speaker = ContentParams.id(entry, "speaker", assetPath, report);
        }
        if (report.errors().size() > errors) {
            return;
        }
        MediaAsset asset = new MediaAsset(id, packageId, kind, sound, localized, music, speaker,
                text(entry, "subtitle"), text(entry, "subtitleKey"), durationMillis, volume, pitch, spatial, channel, interruption);
        if (into.putIfAbsent(id, asset) != null) {
            report.error(DiagnosticCode.DUPLICATE_ID, assetPath, "media " + id + " is defined twice");
        }
    }

    /** Every asset's speaker must be defined in this release. */
    public static void checkSpeakers(Map<NamespacedId, MediaAsset> media, Map<NamespacedId, Speaker> speakers, DiagnosticReport report) {
        for (MediaAsset asset : media.values()) {
            if (asset.speaker() != null && !speakers.containsKey(asset.speaker())) {
                report.error(DiagnosticCode.MISSING_REFERENCE, asset.packageId() + "/media<" + asset.id() + ">",
                        "names unknown speaker '" + asset.speaker() + "'");
            }
        }
    }

    private static void checkSound(MediaCatalog catalog, String sound, String path, DiagnosticReport report) {
        if (Boolean.FALSE.equals(catalog.soundExists(sound))) {
            report.warning(DiagnosticCode.MISSING_ASSET, path, "SoundEvent '" + sound
                    + "' is not loaded on this server; listeners get the subtitle only");
        }
    }

    private static String text(JsonNode entry, String field) {
        JsonNode value = entry.get(field);
        return value == null || value.isNull() || value.asText("").isBlank() ? null : value.asText().trim();
    }
}
