package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestContentLoaderTest {
    @TempDir
    private Path tempDir;

    @Test
    void documentedBetonStyleExampleLoadsAsRealContent() throws IOException {
        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(Path.of("examples/packages"));

        assertTrue(content.quests().containsKey("beton_style_adventure:first_route"));
        assertTrue(content.schedules().containsKey("beton_style_adventure:daily_route_reset"));
        assertTrue(content.cancelers().containsKey("beton_style_adventure:leave_route"));
        assertTrue(content.playerHiders().containsKey("beton_style_adventure:phased_scouts"));
        assertEquals("Success", content.notifications()
                .get("beton_style_adventure:quest_update").path("style").asText());
        assertTrue(content.quests().get("beton_style_adventure:first_route")
                .objectives().stream().anyMatch(objective -> objective.bool("shared", false)));
    }

    @Test
    void loadsRecursiveYamlPackagesTemplatesAndNamedInstructions() throws IOException {
        Path packages = Files.createDirectories(tempDir.resolve("packages"));
        Path template = Files.createDirectories(tempDir.resolve("templates/combat"));
        Files.writeString(template.resolve("package.yml"), """
                package:
                  version: 1
                conditions:
                  eligible: 'tag patrol_unlocked'
                objectives:
                  shared_kills: 'mobkill Bandit 3 name:"Defeat bandits"'
                events:
                  reward_notice: 'notify "Patrol complete!" category:success'
                """);

        Path questPackage = Files.createDirectories(packages.resolve("story/chapter_one"));
        Files.writeString(questPackage.resolve("package.yml"), """
                package:
                  version: 1.0.0
                  templates: [combat]
                """);
        Path scripts = Files.createDirectories(questPackage.resolve("scripts"));
        Files.writeString(scripts.resolve("patrol.yml"), """
                quests:
                  patrol:
                    displayName: Bandit Patrol
                    startConditions: [eligible]
                    objectives: [shared_kills]
                    rewards: [reward_notice]
                items:
                  patrol_token:
                    type: simple
                    item: Quest_Token
                constants:
                  patrol_target: Bandit
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(packages);

        assertTrue(content.packages().contains("story/chapter_one"));
        assertEquals(List.of("combat"), content.packageMetadata().get("story/chapter_one").templates());
        assertEquals("tag", content.conditions().get("story/chapter_one:eligible").type());
        assertEquals("notification", content.events().get("story/chapter_one:reward_notice").type());
        assertEquals("kill", content.quests().get("story/chapter_one:patrol").objectives().getFirst().type());
        assertEquals(3, content.quests().get("story/chapter_one:patrol").objectives().getFirst().integer("amount", 0));
        assertTrue(content.items().containsKey("story/chapter_one:patrol_token"));
        assertEquals("Bandit", content.constants().get("story/chapter_one:patrol_target").asText());
    }

    @Test
    void disabledYamlPackageIsDiscoveredButNotLoaded() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("disabled"));
        Files.writeString(packageDir.resolve("package.yml"), """
                package:
                  enabled: false
                quests:
                  hidden:
                    objectives:
                      - { id: wait, type: timer, amount: 1 }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertFalse(content.packages().contains("disabled"));
        assertTrue(content.packageMetadata().containsKey("disabled"));
        assertFalse(content.quests().containsKey("disabled:hidden"));
    }

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
    void loadsNestedCompositeConditionsAndBranchingEvents() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("quests.json"), """
                {
                  "quests": [
                    {
                      "id": "composite",
                      "startConditions": [
                        {
                          "type": "and",
                          "conditions": [
                            { "type": "tag", "tag": "arrived" },
                            {
                              "type": "or",
                              "conditions": [
                                { "type": "questCompleted", "quest": "intro" },
                                { "type": "permission", "permission": "mysticquests.vip" }
                              ]
                            }
                          ]
                        }
                      ],
                      "objectives": [
                        { "id": "talk", "type": "dialogue", "target": "tutorial:elder", "amount": 1 }
                      ],
                      "rewards": [
                        {
                          "type": "if",
                          "conditions": [ { "type": "tag", "tag": "hardmode" } ],
                          "then": [ { "type": "giveItem", "item": "hytale:gold", "amount": 5 } ],
                          "else": [ { "type": "giveItem", "item": "hytale:gold", "amount": 1 } ]
                        },
                        {
                          "type": "folder",
                          "events": [ { "type": "addTag", "tag": "done" } ]
                        }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertTrue(content.quests().containsKey("tutorial:composite"));
    }

    @Test
    void rejectsCompositeConditionWithoutChildren() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "empty_and",
                    "startConditions": [ { "type": "and" } ],
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("declares no nested conditions"), exception.getMessage());
    }

    @Test
    void rejectsUnsupportedTypesInsteadOfSilentlyIgnoringThem() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "silently_ignored",
                    "startConditions": [ { "type": "level", "value": 10 } ],
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ],
                    "rewards": [ { "type": "hidePlayer" } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("unsupported start condition type 'level'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("unsupported reward type 'hidePlayer'"), exception.getMessage());
    }

    @Test
    void rejectsUnknownTypeNestedInsideComposite() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "bad_nesting",
                    "startConditions": [
                      { "type": "not", "conditions": [ { "type": "notReal" } ] }
                    ],
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("unknown start condition type notReal"), exception.getMessage());
    }

    @Test
    void loadsAbandonCooldownAndReacceptConditions() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("quests.json"), """
                {
                  "quests": [
                    {
                      "id": "retryable",
                      "abandonCooldownSeconds": 3600,
                      "reacceptConditions": [
                        { "type": "tag", "tag": "spoke_to_guide" }
                      ],
                      "objectives": [
                        { "id": "talk", "type": "dialogue", "target": "tutorial:guide", "amount": 1 }
                      ]
                    },
                    {
                      "id": "one_shot",
                      "objectives": [
                        { "id": "talk", "type": "dialogue", "target": "tutorial:guide", "amount": 1 }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals(3600, content.quests().get("tutorial:retryable").abandonCooldownSeconds());
        assertEquals(1, content.quests().get("tutorial:retryable").reacceptConditions().size());
        assertFalse(content.quests().get("tutorial:retryable").abandonIsPermanent());
        // No cooldown and no conditions means abandoning locks the quest for good.
        assertTrue(content.quests().get("tutorial:one_shot").abandonIsPermanent());
    }

    @Test
    void rejectsNegativeAbandonCooldown() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "bad_cooldown",
                    "abandonCooldownSeconds": -5,
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("negative abandonCooldownSeconds"), exception.getMessage());
    }

    @Test
    void rejectsUnknownTypeInReacceptConditions() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "bad_reaccept",
                    "reacceptConditions": [ { "type": "notReal" } ],
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("unknown reaccept condition type notReal"), exception.getMessage());
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
