package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveMarker;
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
    void loadsStepsAndTheirShortForm() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("story"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "declared",
                    "stages": [
                      { "id": "discover", "displayName": "Discover Hyzion" }
                    ],
                    "objectives": [
                      { "id": "look", "type": "reachLocation", "stage": "discover" }
                    ]
                  },
                  {
                    "id": "short_form",
                    "stages": ["discover", "keepers"],
                    "objectives": [
                      { "id": "look", "type": "reachLocation", "stage": "keepers" }
                    ]
                  }
                ]
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals("Discover Hyzion", content.quests().get("story:declared").stages().getFirst().displayName());
        assertEquals("discover", content.quests().get("story:declared").objectives().getFirst().stage());
        // The short form carries only ids; the display name falls back to the id.
        assertEquals(List.of("discover", "keepers"),
                content.quests().get("story:short_form").stages().stream().map(stage -> stage.id()).toList());
        assertEquals("keepers", content.quests().get("story:short_form").stages().get(1).displayName());
    }

    /**
     * A quest may leave stages undeclared and have them derived from its objectives, but once it
     * declares them a misspelled reference would quietly add a step nobody wrote.
     */
    @Test
    void rejectsObjectivesNamingAnUndeclaredStage() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "typo",
                    "stages": [{ "id": "discover" }],
                    "objectives": [
                      { "id": "look", "type": "reachLocation", "stage": "discovr" }
                    ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class,
                () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("undeclared stage discovr"), exception.getMessage());
    }

    @Test
    void readsAnObjectiveMapMarker() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("story"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "ruins",
                    "objectives": [
                      { "id": "search", "type": "reachLocation",
                        "marker": { "world": "default", "x": 120.5, "y": 64, "z": -88, "label": "Druid Ruins", "area": true } },
                      { "id": "report", "type": "custom" }
                    ]
                  }
                ]
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);
        List<ObjectiveDefinition> objectives = content.quests().get("story:ruins").objectives();

        ObjectiveMarker marker = ObjectiveMarker.of(objectives.get(0)).orElseThrow();
        assertEquals("default", marker.world());
        assertEquals(120.5D, marker.x());
        assertEquals("Druid Ruins", marker.label());
        assertTrue(marker.area());
        assertEquals(ObjectiveMarker.DEFAULT_ICON, marker.icon());
        assertTrue(ObjectiveMarker.of(objectives.get(1)).isEmpty());
    }

    /** A marker that cannot be placed is a content bug, so it fails the load instead of vanishing. */
    @Test
    void rejectsAMalformedObjectiveMarker() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("broken"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "lost",
                    "objectives": [
                      { "id": "find", "type": "reachLocation", "marker": { "world": "default", "x": 1, "y": "up", "z": 3 } },
                      { "id": "icon", "type": "reachLocation", "marker": { "world": "default", "x": 1, "y": 2, "z": 3, "icon": "../../evil.png" } }
                    ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class,
                () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("broken:lost/find has an invalid marker: marker y must be a number"),
                exception.getMessage());
        assertTrue(exception.getMessage().contains("broken:lost/icon has an invalid marker"), exception.getMessage());
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
        assertEquals(List.of("hello"),
                content.conversations().get("tutorial:elder_intro").startCandidates());
        assertEquals("NpcEntity", content.conversations().get("tutorial:elder_intro").entity().type());
    }

    /**
     * An NPC has to be able to open differently once something has changed, which is what an ordered
     * list of entry points buys. The single-string form is the same content it always was.
     */
    @Test
    void acceptsSeveralConversationEntryPointsInAuthorOrder() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("conversations.json"), """
                {
                  "conversations": [
                    {
                      "id": "elder_intro",
                      "start": ["after_quest", "during_quest", "hello"],
                      "nodes": [
                        {
                          "id": "after_quest",
                          "text": "Thanks again.",
                          "conditions": [ { "type": "tag", "tag": "tutorial_complete" } ]
                        },
                        {
                          "id": "during_quest",
                          "text": "Found them yet?",
                          "conditions": [ { "type": "tag", "tag": "tutorial_started" } ]
                        },
                        { "id": "hello", "text": "Welcome." }
                      ]
                    }
                  ]
                }
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals(List.of("after_quest", "during_quest", "hello"),
                content.conversations().get("tutorial:elder_intro").startCandidates());
    }

    /** A typo in a later entry point is a load error, not an NPC that goes quiet months later. */
    @Test
    void rejectsAnEntryPointThatNamesNoNode() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("conversations.json"), """
                {
                  "conversations": [
                    {
                      "id": "elder_intro",
                      "start": ["hello", "after_qeust"],
                      "nodes": [ { "id": "hello", "text": "Welcome." } ]
                    }
                  ]
                }
                """);

        IOException exception = assertThrows(IOException.class,
                () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("start node does not exist: after_qeust"));
    }

    /**
     * MysticGeneration NPCs are bound by definition or stable identity rather than by the entity
     * fields, which a republished definition invalidates.
     */
    @Test
    void loadsMysticGenerationConversationBindingsAndObjectives() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("tutorial"));
        Files.writeString(packageDir.resolve("conversations.json"), """
                {
                  "conversations": [
                    {
                      "id": "guard_intro",
                      "speaker": "Avalon Guard",
                      "start": "hello",
                      "entity": {
                        "generationDefinition": "hyzion:avalon_guard",
                        "generationUuid": "4d6b1f3a-2c55-4f0e-9a1b-77c0d2e4b5a6"
                      },
                      "nodes": [
                        { "id": "hello", "text": "Halt." }
                      ]
                    }
                  ]
                }
                """);
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "meet_the_guard",
                    "objectives": [
                      { "id": "greet", "type": "interactNpc", "target": "hyzion:avalon_guard" }
                    ]
                  }
                ]
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals("hyzion:avalon_guard",
                content.conversations().get("tutorial:guard_intro").entity().generationDefinition());
        assertEquals("4d6b1f3a-2c55-4f0e-9a1b-77c0d2e4b5a6",
                content.conversations().get("tutorial:guard_intro").entity().generationUuid());
        assertEquals("hyzion:avalon_guard", content.quests().get("tutorial:meet_the_guard")
                .objectives().get(0).text("target", ""));
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
                    "rewards": [ { "type": "packetEffect" } ]
                  }
                ]
                """);

        IOException exception = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(tempDir));

        assertTrue(exception.getMessage().contains("unsupported start condition type 'level'"), exception.getMessage());
        assertTrue(exception.getMessage().contains("unsupported reward type 'packetEffect'"), exception.getMessage());
    }

    /**
     * Visibility, targeting, and screen packets used to be rejected as unimplemented. They are real
     * event and condition types now, so content using them must load.
     */
    @Test
    void acceptsVisibilityTargetingAndPacketTypes() throws IOException {
        Path packageDir = Files.createDirectories(tempDir.resolve("scene"));
        Files.writeString(packageDir.resolve("quests.json"), """
                [
                  {
                    "id": "cutscene",
                    "startConditions": [ { "type": "playerHidden", "target": "self" } ],
                    "objectives": [ { "id": "talk", "type": "dialogue", "target": "x", "amount": 1 } ],
                    "startEvents": [
                      { "type": "hidePlayer", "target": "players" },
                      { "type": "preventTargeting" },
                      { "type": "setCamera", "mode": "third", "locked": true },
                      { "type": "sendTitle", "title": "Chapter One" }
                    ],
                    "rewards": [
                      { "type": "showPlayer", "target": "players" },
                      { "type": "allowTargeting" },
                      { "type": "actionBar", "message": "Done" }
                    ]
                  }
                ]
                """);

        LoadedContent content = new QuestContentLoader(Json.createMapper()).load(tempDir);

        assertEquals(4, content.quests().get("scene:cutscene").startEvents().size());
        assertEquals(3, content.quests().get("scene:cutscene").rewards().size());
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
