package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.MutableClock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §14: uploads are checked as Ogg Vorbis and built into a pack laid out as the engine loads it. */
final class StudioAudioTest {
    @TempDir
    Path data;

    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));

    /** A minimal Ogg Vorbis stream: an identification page, then a last page whose granule gives the length. */
    static byte[] vorbis(int channels, int sampleRate, double seconds) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        ByteBuffer identification = ByteBuffer.allocate(30).order(ByteOrder.LITTLE_ENDIAN);
        identification.put((byte) 1).put("vorbis".getBytes(StandardCharsets.US_ASCII)).putInt(0)
                .put((byte) channels).putInt(sampleRate).putInt(0).putInt(128000).putInt(0).put((byte) 0xB8).put((byte) 1);
        out.writeBytes(page(0, identification.array()));
        out.writeBytes(page((long) (seconds * sampleRate), new byte[] {0}));
        return out.toByteArray();
    }

    private static byte[] page(long granule, byte[] packet) {
        ByteBuffer page = ByteBuffer.allocate(27 + 1 + packet.length).order(ByteOrder.LITTLE_ENDIAN);
        page.put("OggS".getBytes(StandardCharsets.US_ASCII)).put((byte) 0).put((byte) 0).putLong(granule)
                .putInt(7).putInt(0).putInt(0).put((byte) 1).put((byte) packet.length).put(packet);
        return page.array();
    }

    @Test
    void uploadsAreCheckedAsOggVorbis() throws Exception {
        StudioAudio.Vorbis read = StudioAudio.inspect(vorbis(1, 48000, 2.5));
        assertEquals(1, read.channels());
        assertEquals(48000, read.sampleRate());
        assertEquals(2.5, read.seconds(), 0.001);

        StudioAudio audio = new StudioAudio(data.resolve("studio"), json, clock);
        StudioException wav = assertThrows(StudioException.class,
                () -> audio.upload("greeting", StudioAudio.Kind.VOICE, "RIFF....WAVEfmt ".repeat(5).getBytes(StandardCharsets.US_ASCII), "lead"));
        assertTrue(wav.getMessage().contains("Ogg Vorbis"), wav.getMessage());
        byte[] opus = vorbis(1, 48000, 1);
        System.arraycopy("OpusHead".getBytes(StandardCharsets.US_ASCII), 0, opus, 28, 8);
        assertThrows(StudioException.class, () -> audio.upload("greeting", StudioAudio.Kind.VOICE, opus, "lead"));
        assertThrows(StudioException.class, () -> audio.upload("../escape", StudioAudio.Kind.VOICE, vorbis(1, 48000, 1), "lead"),
                "clip names cannot leave the audio folder");

        StudioAudio.Upload stereo = audio.upload("warden_greeting", StudioAudio.Kind.VOICE, vorbis(2, 44100, 3), "lead");
        assertFalse(stereo.warnings().isEmpty(), "a stereo voice line is flagged: it will not sound positional");
        assertEquals("MysticQuests_Voice_warden_greeting", stereo.clip().soundEvent());
    }

    @Test
    void thePackIsLaidOutAsTheEngineLoadsItAndRebuildsCleanly() throws Exception {
        StudioAudio audio = new StudioAudio(data.resolve("studio"), json, clock);
        audio.upload("warden_greeting", StudioAudio.Kind.VOICE, vorbis(1, 48000, 2), "lead");
        audio.upload("seal_break", StudioAudio.Kind.SFX, vorbis(1, 48000, 1), "lead");
        Path pack = data.resolve(StudioAudio.PACK_FOLDER);

        StudioAudio.Build first = audio.build(pack, ">=0.6.0 <0.7.0");
        assertEquals(2, first.sounds());
        assertTrue(Files.isRegularFile(pack.resolve("Common/Sounds/MysticQuests/Voice/warden_greeting.ogg")));
        JsonNode event = json.readTree(pack.resolve("Server/Audio/SoundEvents/MysticQuests/MysticQuests_Voice_warden_greeting.json").toFile());
        assertEquals("SFX_Attn_Moderate", event.path("Parent").asText());
        assertEquals("Sounds/MysticQuests/Voice/warden_greeting.ogg", event.path("Layers").get(0).path("Files").get(0).asText(),
                "sound layers name files under Sounds/, relative to Common");
        JsonNode manifest = json.readTree(pack.resolve("manifest.json").toFile());
        assertEquals("mysticquests-generated", manifest.path("Name").asText());
        assertEquals(">=0.6.0 <0.7.0", manifest.path("ServerVersion").asText());
        assertEquals("1.0.1", manifest.path("Version").asText());

        audio.delete("seal_break");
        StudioAudio.Build second = audio.build(pack, ">=0.6.0 <0.7.0");
        assertEquals(1, second.sounds());
        assertEquals(2, second.removed(), "the deleted clip's sound file and event are removed");
        assertFalse(Files.exists(pack.resolve("Common/Sounds/MysticQuests/SFX/seal_break.ogg")));
        assertEquals("1.0.2", json.readTree(pack.resolve("manifest.json").toFile()).path("Version").asText());
    }
}
