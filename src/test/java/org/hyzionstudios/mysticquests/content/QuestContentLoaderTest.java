package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestContentLoaderTest {
    @TempDir
    private Path tempDir;

    @Test
    void loadsTypedJsonPackageWithNamespacedQuestIds() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("quests.json"), """
                {
                  "quests": [
                    {
                      "id": "starter_hunt",
                      "displayName": "Starter Hunt",
                      "objectives": [
                        { "id": "kill_boars", "type": "kill", "entity": "hytale:boar", "amount": 3 }
                      ],
                      "rewards": [
                        { "type": "addTag", "tag": "tutorial_complete" }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals(1, content.packages().size());
        assertTrue(content.quests().containsKey("tutorial:starter_hunt"));
        assertEquals("Starter Hunt", content.quests().get("tutorial:starter_hunt").displayName());
    }

    @Test
    void rejectsUnknownObjectiveTypesBeforeRegistrySwap() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "bad",
                    "objectives": [
                      { "id": "strange", "type": "notReal" }
                    ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("unknown objective type notReal"));
    }

    @Test
    void loadsConversationEntityBindingsAndNotificationEvents() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("conversations.json"), """
                {
                  "conversations": [
                    {
                      "id": "elder_intro",
                      "speaker": "Village Elder",
                      "start": "hello",
                      "entity": {
                        "uuid": "8b348e13-f3df-4d26-8b86-45e7f17c7157",
                        "type": "NpcEntity",
                        "name": "Elder Rowan"
                      },
                      "nodes": [
                        {
                          "id": "hello",
                          "text": "Welcome.",
                          "events": [
                            { "type": "notification", "title": "Quest", "body": "Listen closely.", "style": "Success" }
                          ],
                          "choices": [
                            { "text": "Continue", "next": "end" }
                          ]
                        }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals("Village Elder", content.conversations().get("tutorial:elder_intro").speaker());
        assertEquals("hello", content.conversations().get("tutorial:elder_intro").start());
        assertEquals("NpcEntity", content.conversations().get("tutorial:elder_intro").entity().type());
    }

    @Test
    void acceptsScopedStateAliasesInTypedJson() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("quests.json"), """
                {
                  "quests": [
                    {
                      "id": "scoped",
                      "startConditions": [
                        { "type": "globalTag", "tag": "festival_open" },
                        { "type": "entityVariable", "key": "spoken", "value": "true" },
                        { "type": "volumeVariable", "key": "enabled", "value": "true" }
                      ],
                      "objectives": [
                        { "id": "talk", "type": "dialogue", "target": "tutorial:elder_intro", "amount": 1 }
                      ],
                      "rewards": [
                        { "type": "blockTag", "tag": "opened" },
                        { "type": "volumeTag", "tag": "visited" },
                        { "type": "incrementVariable", "scope": "global", "key": "daily_completions", "amount": 1 }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertTrue(content.quests().containsKey("tutorial:scoped"));
    }

    @Test
    void rejectsConversationChoiceWithMissingNextNode() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("conversations.json"), """
                [
                  {
                    "id": "bad_talk",
                    "nodes": [
                      {
                        "id": "hello",
                        "choices": [
                          { "text": "Continue", "next": "missing" }
                        ]
                      }
                    ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("choice points to missing node missing"));
    }
}
