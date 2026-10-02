package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestAuthoringServiceTest {
    @TempDir
    Path temp;

    @Test
    void yamlSourcePublishingIsTransactional() throws IOException {
        Path packages = Files.createDirectories(temp.resolve("packages"));
        var mapper = Json.createMapper();
        var loaded = new AtomicReference<>(LoadedContent.empty());
        QuestContentLoader loader = new QuestContentLoader(mapper);
        QuestAuthoringService service = new QuestAuthoringService(
                packages, mapper, loaded::get, () -> {
                    LoadedContent next = loader.load(packages);
                    loaded.set(next);
                    return next;
                });

        String valid = """
                actions:
                  hello: 'message "Hello adventurer"'
                quests:
                  intro:
                    displayName: Introduction
                    objectives:
                      - id: talk
                        type: dialogue
                        target: guide
                    startEvents: [hello]
                """;
        service.saveSource("story", "scripts/main.yml", valid);
        assertTrue(loaded.get().quests().containsKey("story:intro"));

        IOException error = assertThrows(IOException.class, () -> service.saveSource("story", "scripts/main.yml", """
                quests:
                  intro:
                    displayName: Broken
                    startConditions: [missing_condition]
                    objectives:
                      - id: talk
                        type: dialogue
                        target: guide
                """));

        assertTrue(error.getMessage().contains("missing_condition"), error.getMessage());
        assertEquals(valid, Files.readString(packages.resolve("story/scripts/main.yml")));
        assertEquals("Introduction", loaded.get().quests().get("story:intro").displayName());
    }

    /**
     * The studio form has no stage inputs. Saving a quest through it must not flatten steps that
     * were authored in the package file — losing them would silently rewrite what the HUD shows.
     */
    @Test
    void publishingFromTheStudioKeepsStepsTheFormCannotEdit() throws IOException {
        Path packages = Files.createDirectories(temp.resolve("packages"));
        var mapper = Json.createMapper();
        var loaded = new AtomicReference<>(LoadedContent.empty());
        QuestContentLoader loader = new QuestContentLoader(mapper);
        QuestAuthoringService service = new QuestAuthoringService(
                packages, mapper, loaded::get, () -> {
                    LoadedContent next = loader.load(packages);
                    loaded.set(next);
                    return next;
                });

        Files.createDirectories(packages.resolve("story"));
        Files.writeString(packages.resolve("story/quests.json"), """
                {
                  "quests": [
                    {
                      "id": "intro",
                      "displayName": "Introduction",
                      "stages": [
                        { "id": "discover", "displayName": "Discover Hyzion" },
                        { "id": "keepers", "displayName": "Seek the Keepers" }
                      ],
                      "objectives": [
                        { "id": "talk", "type": "dialogue", "target": "guide", "stage": "discover" },
                        { "id": "seek", "type": "dialogue", "target": "keeper", "stage": "keepers" }
                      ]
                    }
                  ]
                }
                """);
        loaded.set(loader.load(packages));

        service.save(new QuestAuthoringService.QuestDraft(
                "story",
                "intro",
                "Introduction Rewritten",
                "",
                false,
                "",
                java.util.List.of(
                        new QuestAuthoringService.ObjectiveDraft("talk", "dialogue", "Talk", "guide", 1),
                        new QuestAuthoringService.ObjectiveDraft("seek", "dialogue", "Seek", "keeper", 1)),
                "", "", "", "", "", ""));

        var quest = loaded.get().quests().get("story:intro");
        assertEquals("Introduction Rewritten", quest.displayName());
        assertEquals(2, quest.stages().size());
        assertEquals("Discover Hyzion", quest.stages().getFirst().displayName());
        assertEquals("discover", quest.objectives().getFirst().stage());
        assertEquals("keepers", quest.objectives().get(1).stage());
    }

    @Test
    void sourceEditorRejectsTraversal() {
        QuestAuthoringService service = new QuestAuthoringService(
                temp, Json.createMapper(), LoadedContent::empty, LoadedContent::empty);
        assertThrows(IOException.class, () -> service.loadSource("story", "../outside.yml"));
    }
}
