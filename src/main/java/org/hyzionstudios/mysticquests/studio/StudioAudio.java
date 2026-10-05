package org.hyzionstudios.mysticquests.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * The Studio's audio pipeline (§14): creators upload Ogg Vorbis files, the Studio keeps each upload
 * as its master, checks it, and builds a generated asset pack with one SoundEvent per clip that
 * story media can name in {@code sound}.
 *
 * <p>The generated pack follows the engine's layout (verified against the 0.6.8 sources):
 * {@code Common/Sounds/...ogg}, because sound layers only accept {@code .ogg} files under
 * {@code Sounds/}, and {@code Server/Audio/SoundEvents/...json}, the SoundEvent asset path. Each
 * event inherits attenuation from the vanilla {@code SFX_Attn_Moderate}.
 *
 * <p>Only Ogg Vorbis is accepted: Hytale reads nothing else, and converting WAV or FLAC needs an
 * encoder the server does not have. Music is not generated here, because the music system plays
 * MusicContainers, not single sound events.
 */
public final class StudioAudio {
    public static final long MAX_BYTES = 8L * 1024 * 1024;
    public static final String PACK_FOLDER = "MysticQuests-Generated";
    static final String PARENT = "SFX_Attn_Moderate";
    private static final Pattern NAME = Pattern.compile("[a-z0-9][a-z0-9_]{0,47}");

    /** What a clip is for; matches the story media kinds that play a sound event. */
    public enum Kind {
        VOICE("Voice"), SFX("SFX"), AMBIENT("Ambient"), STINGER("Stinger"), UI("UI"), CINEMATIC("Cinematic");

        private final String folder;

        Kind(String folder) {
            this.folder = folder;
        }

        public String folder() {
            return folder;
        }

        public static Kind parse(String raw) throws StudioException {
            try {
                return valueOf(raw.strip().toUpperCase(Locale.ROOT));
            } catch (RuntimeException unknown) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, "Kind must be voice, sfx, ambient, stinger, ui or cinematic.");
            }
        }
    }

    /** One uploaded sound and what the Studio found in it. */
    public record Clip(String name, Kind kind, int channels, int sampleRate, double seconds, long bytes, String hash,
                       String uploadedBy, Instant uploadedAt) {
        /** The SoundEvent id story media names in {@code sound}. */
        public String soundEvent() {
            return "MysticQuests_" + kind.folder() + "_" + name;
        }

        /** The sound file inside the generated pack's Common folder. */
        public String file() {
            return "Sounds/MysticQuests/" + kind.folder() + "/" + name + ".ogg";
        }
    }

    /** An upload's result: the clip and anything worth knowing about it. */
    public record Upload(Clip clip, List<String> warnings) {
    }

    /** A built pack: how many sounds it holds and how many stale files were removed. Loaded at the next restart. */
    public record Build(int sounds, int removed, String folder) {
    }

    record Vorbis(int channels, int sampleRate, double seconds) {
    }

    private final Path root;
    private final Path index;
    private final ObjectMapper json;
    private final Clock clock;

    /** @param studioRoot the Studio's folder; masters are kept in {@code audio/} under it */
    public StudioAudio(Path studioRoot, ObjectMapper json, Clock clock) {
        this.root = studioRoot.resolve("audio");
        this.index = root.resolve("index.json");
        this.json = json;
        this.clock = clock;
    }

    public synchronized List<Clip> clips() throws IOException {
        return List.copyOf(read().values());
    }

    /**
     * Stores an upload as the clip {@code name}, replacing an earlier upload of that name.
     *
     * @throws StudioException {@code BAD_REQUEST} for a bad name or a file that is not Ogg Vorbis,
     *         {@code TOO_LARGE} over {@link #MAX_BYTES}
     */
    public synchronized Upload upload(String name, Kind kind, byte[] bytes, String actor) throws IOException, StudioException {
        String clipName = name == null ? "" : name.strip().toLowerCase(Locale.ROOT);
        if (!NAME.matcher(clipName).matches()) {
            throw new StudioException(StudioException.Status.BAD_REQUEST,
                    "Clip names use lowercase letters, digits and _, start with a letter or digit, and are at most 48 characters.");
        }
        if (bytes.length > MAX_BYTES) {
            throw new StudioException(StudioException.Status.TOO_LARGE, "Sound files are limited to " + (MAX_BYTES / 1024 / 1024) + " MiB.");
        }
        Vorbis vorbis = inspect(bytes);
        List<String> warnings = new ArrayList<>();
        if (vorbis.channels() > 1 && kind != Kind.UI && kind != Kind.CINEMATIC) {
            warnings.add("This file is stereo. Sounds played at an NPC or a place should be mono, or they will not "
                    + "sound like they come from there.");
        }
        if (vorbis.seconds() > 60 && kind != Kind.AMBIENT && kind != Kind.CINEMATIC) {
            warnings.add("This clip is over a minute long; long beds belong in ambience or music.");
        }
        Files.createDirectories(root);
        ContentRoot.writeAtomically(root.resolve(clipName + ".ogg"), bytes);
        Clip clip = new Clip(clipName, kind, vorbis.channels(), vorbis.sampleRate(), Math.round(vorbis.seconds() * 100) / 100.0,
                bytes.length, ContentRoot.hash(bytes), actor, clock.instant());
        Map<String, Clip> clips = read();
        clips.put(clipName, clip);
        write(clips);
        return new Upload(clip, warnings);
    }

    public synchronized byte[] bytes(String name) throws IOException, StudioException {
        Map<String, Clip> clips = read();
        if (name == null || !clips.containsKey(name)) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "No clip " + name + ".");
        }
        return Files.readAllBytes(root.resolve(name + ".ogg"));
    }

    public synchronized void delete(String name) throws IOException, StudioException {
        Map<String, Clip> clips = read();
        if (name == null || clips.remove(name) == null) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "No clip " + name + ".");
        }
        Files.deleteIfExists(root.resolve(name + ".ogg"));
        write(clips);
    }

    /**
     * Writes the generated pack: a manifest, every clip's sound file and its SoundEvent, and removes
     * generated files whose clip is gone. Only the pack's own sound folders are touched.
     *
     * @param pack the pack folder, beside the plugin's data folder in {@code mods/}
     * @param serverVersion the engine range the pack declares, the plugin's own
     */
    public synchronized Build build(Path pack, String serverVersion) throws IOException {
        boolean existed = Files.isRegularFile(pack.resolve("manifest.json"));
        Map<String, Clip> clips = read();
        Path sounds = pack.resolve("Common/Sounds/MysticQuests");
        Path events = pack.resolve("Server/Audio/SoundEvents/MysticQuests");
        Files.createDirectories(sounds);
        Files.createDirectories(events);

        // Each build bumps the patch version, so the pack's version says which build a server runs.
        int build = 1;
        if (existed) {
            String previous = json.readTree(pack.resolve("manifest.json").toFile()).path("Version").asText("1.0.0");
            try {
                build = Integer.parseInt(previous.substring(previous.lastIndexOf('.') + 1)) + 1;
            } catch (NumberFormatException unnumbered) {
                build = 1;
            }
        }
        ObjectNode manifest = json.createObjectNode();
        manifest.put("Group", "org.hyzionstudios");
        manifest.put("Name", "mysticquests-generated");
        manifest.put("Version", "1.0." + build);
        manifest.put("Description", "Sounds uploaded through the MysticQuests Studio. Generated: changes here are overwritten.");
        manifest.put("ServerVersion", serverVersion);
        ContentRoot.writeAtomically(pack.resolve("manifest.json"), json.writerWithDefaultPrettyPrinter().writeValueAsBytes(manifest));

        Set<Path> wanted = new HashSet<>();
        for (Clip clip : clips.values()) {
            Path file = pack.resolve("Common").resolve(clip.file());
            Path event = events.resolve(clip.soundEvent() + ".json");
            wanted.add(file.normalize());
            wanted.add(event.normalize());
            byte[] master = Files.readAllBytes(root.resolve(clip.name() + ".ogg"));
            if (!Files.isRegularFile(file) || !ContentRoot.hash(Files.readAllBytes(file)).equals(clip.hash())) {
                ContentRoot.writeAtomically(file, master);
            }
            ObjectNode soundEvent = json.createObjectNode();
            soundEvent.put("Parent", PARENT);
            soundEvent.put("Volume", 0);
            soundEvent.put("MaxInstance", 8);
            ArrayNode layers = soundEvent.putArray("Layers");
            layers.addObject().putArray("Files").add(clip.file());
            ContentRoot.writeAtomically(event, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(soundEvent));
        }
        int removed = 0;
        for (Path folder : List.of(sounds, events)) {
            try (Stream<Path> files = Files.walk(folder)) {
                for (Path file : files.filter(Files::isRegularFile).toList()) {
                    if (!wanted.contains(file.normalize())) {
                        Files.delete(file);
                        removed++;
                    }
                }
            }
        }
        return new Build(clips.size(), removed, pack.getFileName().toString());
    }

    // --- Ogg Vorbis ---

    /**
     * Reads what Hytale needs from an Ogg Vorbis file: its channels and sample rate (from the
     * identification header in the first page) and its length (the last page's granule position).
     */
    static Vorbis inspect(byte[] bytes) throws StudioException {
        if (bytes.length < 58 || !ascii(bytes, 0, "OggS")) {
            throw new StudioException(StudioException.Status.BAD_REQUEST,
                    "That is not an Ogg file. Hytale plays Ogg Vorbis (.ogg); export or convert to it first.");
        }
        int segments = bytes[26] & 0xFF;
        int packet = 27 + segments;
        if (bytes.length < packet + 30) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The Ogg file is truncated.");
        }
        if (ascii(bytes, packet, "OpusHead")) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "That is Ogg Opus. Hytale plays Ogg Vorbis; re-export as Vorbis.");
        }
        if (bytes[packet] != 1 || !ascii(bytes, packet + 1, "vorbis")) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "That Ogg file does not hold Vorbis audio.");
        }
        ByteBuffer header = ByteBuffer.wrap(bytes, packet + 11, 5).order(ByteOrder.LITTLE_ENDIAN);
        int channels = header.get() & 0xFF;
        int sampleRate = header.getInt();
        if (channels < 1 || channels > 2 || sampleRate <= 0) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "Only mono or stereo Vorbis files are supported.");
        }
        long granule = 0;
        for (int offset = bytes.length - 27; offset >= 0; offset--) {
            if (ascii(bytes, offset, "OggS")) {
                granule = ByteBuffer.wrap(bytes, offset + 6, 8).order(ByteOrder.LITTLE_ENDIAN).getLong();
                break;
            }
        }
        return new Vorbis(channels, sampleRate, granule > 0 ? (double) granule / sampleRate : 0);
    }

    private static boolean ascii(byte[] bytes, int offset, String expected) {
        if (offset < 0 || offset + expected.length() > bytes.length) {
            return false;
        }
        byte[] wanted = expected.getBytes(StandardCharsets.US_ASCII);
        for (int index = 0; index < wanted.length; index++) {
            if (bytes[offset + index] != wanted[index]) {
                return false;
            }
        }
        return true;
    }

    // --- Index ---

    private Map<String, Clip> read() throws IOException {
        Map<String, Clip> clips = new TreeMap<>();
        if (!Files.isRegularFile(index)) {
            return clips;
        }
        for (JsonNode node : json.readTree(index.toFile()).path("clips")) {
            try {
                Clip clip = new Clip(node.path("name").asText(), Kind.valueOf(node.path("kind").asText()), node.path("channels").asInt(),
                        node.path("sampleRate").asInt(), node.path("seconds").asDouble(), node.path("bytes").asLong(),
                        node.path("hash").asText(), node.path("uploadedBy").asText(), Instant.parse(node.path("uploadedAt").asText()));
                clips.put(clip.name(), clip);
            } catch (RuntimeException unreadable) {
                // One damaged entry must not hide every other clip.
            }
        }
        return clips;
    }

    private void write(Map<String, Clip> clips) throws IOException {
        ObjectNode document = json.createObjectNode();
        ArrayNode list = document.putArray("clips");
        for (Clip clip : clips.values()) {
            list.addObject().put("name", clip.name()).put("kind", clip.kind().name()).put("channels", clip.channels())
                    .put("sampleRate", clip.sampleRate()).put("seconds", clip.seconds()).put("bytes", clip.bytes())
                    .put("hash", clip.hash()).put("uploadedBy", clip.uploadedBy()).put("uploadedAt", clip.uploadedAt().toString());
        }
        ContentRoot.writeAtomically(index, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(document));
    }
}
