package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class BundledPackagesTest {
    private static final ObjectMapper MAPPER = Json.createMapper();

    @TempDir
    private Path root;

    @Test
    void theChapterIsWrittenOnceAndLoadsAsRealContent() throws IOException {
        Path packages = root.resolve("packages");
        Path record = root.resolve("data/bundled-packages.json");

        assertEquals(BundledPackages.Result.INSTALLED,
                BundledPackages.install(packages, record, MAPPER).get("hytale_chapter_1"));
        assertTrue(Files.isRegularFile(packages.resolve("hytale_chapter_1/quests.yml")));
        assertFalse(Files.exists(root.resolve("data/hytale_chapter_1.installing")), "the staging copy is moved, not left behind");

        LoadedContent content = new QuestContentLoader(MAPPER).load(packages);
        QuestDefinition opening = content.quests().get("hytale_chapter_1:the_air_hums");
        assertEquals("worldEvent", opening.startOn().getFirst().type());
        assertTrue(content.quests().containsKey("hytale_chapter_1:the_gobliterator"));
        assertEquals("worldEvent", content.quests().get("hytale_chapter_1:breach_hunter").unlockOn().getFirst().type());
    }

    @Test
    void anEditedOrDeletedPackageIsNeverWrittenAgain() throws IOException {
        Path packages = root.resolve("packages");
        Path record = root.resolve("data/bundled-packages.json");
        BundledPackages.install(packages, record, MAPPER);
        Path quests = packages.resolve("hytale_chapter_1/quests.yml");
        Files.writeString(quests, "quests: {}\n");

        assertEquals(BundledPackages.Result.PREVIOUSLY_INSTALLED,
                BundledPackages.install(packages, record, MAPPER).get("hytale_chapter_1"));
        assertEquals("quests: {}\n", Files.readString(quests));

        Files.delete(quests);
        Files.delete(packages.resolve("hytale_chapter_1/package.yml"));
        Files.delete(packages.resolve("hytale_chapter_1/README.md"));
        Files.delete(packages.resolve("hytale_chapter_1"));
        BundledPackages.install(packages, record, MAPPER);
        assertFalse(Files.exists(packages.resolve("hytale_chapter_1")), "a package the owner deleted stays deleted");
    }

    @Test
    void anExistingDirectoryOfTheSameNameIsTheOwners() throws IOException {
        Path packages = Files.createDirectories(root.resolve("packages/hytale_chapter_1")).getParent();
        Files.writeString(packages.resolve("hytale_chapter_1/quests.yml"), "quests: {}\n");

        Map<String, BundledPackages.Result> results =
                BundledPackages.install(packages, root.resolve("data/bundled-packages.json"), MAPPER);

        assertEquals(BundledPackages.Result.ALREADY_PRESENT, results.get("hytale_chapter_1"));
        assertEquals("quests: {}\n", Files.readString(packages.resolve("hytale_chapter_1/quests.yml")));
    }

    @Test
    void storyObjectivesAreCheckedAtLoad() throws IOException {
        Path packages = Files.createDirectories(root.resolve("packages/broken"));
        Files.writeString(packages.resolve("quests.yml"), """
                quests:
                  typo:
                    startOn:
                      - { type: gather, target: Plant_Fruit_Apple }
                    objectives:
                      - { id: a, type: worldEvent, event: Goblin_Breach_First_Event, phase: openned }
                      - { id: b, type: bossDefeated }
                """);

        IOException failure = assertThrows(IOException.class,
                () -> new QuestContentLoader(MAPPER).load(packages.getParent()));

        assertTrue(failure.getMessage().contains("unknown phase 'openned'"), failure.getMessage());
        assertTrue(failure.getMessage().contains("names no \"boss\""), failure.getMessage());
        assertTrue(failure.getMessage().contains("startOn[0] uses gather"), failure.getMessage());
    }
}
