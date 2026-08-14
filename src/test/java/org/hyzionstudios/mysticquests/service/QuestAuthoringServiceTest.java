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

    @Test
    void sourceEditorRejectsTraversal() {
        QuestAuthoringService service = new QuestAuthoringService(
                temp, Json.createMapper(), LoadedContent::empty, LoadedContent::empty);
        assertThrows(IOException.class, () -> service.loadSource("story", "../outside.yml"));
    }
}
