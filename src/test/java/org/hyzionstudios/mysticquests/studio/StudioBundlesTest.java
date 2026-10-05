package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.MutableClock;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.List;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

/** §23 promotion: a release moves between servers only through the other server's draft, and a bundle cannot escape it. */
final class StudioBundlesTest {
    @TempDir
    Path data;

    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));

    private StudioReleases server(String name, String quests) throws IOException {
        Path live = data.resolve(name).resolve("packages");
        Files.createDirectories(live.resolve("greenvale"));
        Files.writeString(live.resolve("greenvale/quests.yml"), quests);
        StudioWorkspace workspace = new StudioWorkspace(ContentRoot.live(live), data.resolve(name).resolve("studio"), json, Json.createYamlMapper());
        workspace.ensure();
        return new StudioReleases(data.resolve(name).resolve("studio"), workspace,
                draft -> new StudioValidation(true, 1, 1, List.of()), () -> { }, clock, json);
    }

    private static byte[] zip(String... namesAndContents) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (int index = 0; index < namesAndContents.length; index += 2) {
                zip.putNextEntry(new ZipEntry(namesAndContents[index]));
                zip.write(namesAndContents[index + 1].getBytes(StandardCharsets.UTF_8));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    @Test
    void aReleasePromotesThroughTheTargetsDraft() throws Exception {
        StudioReleases development = server("dev", "quests: [ { id: wolves, displayName: Wolf Hunt } ]\n");
        StudioReleases production = server("prod", "quests: [ { id: wolves, displayName: Wolf Trouble } ]\n");
        Path devLive = data.resolve("dev/packages/greenvale/quests.yml");
        Path prodLive = data.resolve("prod/packages/greenvale/quests.yml");
        Files.writeString(data.resolve("dev/studio/workspace/packages/greenvale/extra.yml"), "quests: []\n", StandardCharsets.UTF_8,
                java.nio.file.StandardOpenOption.CREATE_NEW);
        development.publish("dev", "add extra", false);

        byte[] bundle = development.export(1);
        assertEquals(2, production.importBundle(bundle));
        assertEquals("quests: [ { id: wolves, displayName: Wolf Trouble } ]\n", Files.readString(prodLive),
                "an import never touches what players run");
        production.publish("release manager", "promote dev release 1", false);
        assertEquals(Files.readString(devLive), Files.readString(prodLive), "publishing it does");
        assertEquals(true, Files.exists(data.resolve("prod/packages/greenvale/extra.yml")));
    }

    @Test
    void bundlesCannotWriteOutsideTheDraft() throws Exception {
        for (String evil : List.of("packages/../../config.json", "../outside.yml", "/etc/cron.yml", "packages/greenvale/run.sh",
                "data/players.yml", "packages\\..\\..\\x.yml")) {
            assertThrows(StudioException.class, () -> StudioBundles.read(zip(evil, "quests: []")), evil);
        }
        assertThrows(StudioException.class, () -> StudioBundles.read("not a zip".getBytes(StandardCharsets.UTF_8)));
        assertThrows(StudioException.class, () -> StudioBundles.read(zip()), "an empty bundle");
        assertFalse(Files.exists(data.resolve("config.json")));
    }
}
