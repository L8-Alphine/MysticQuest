package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** v1 quests and conversations run 2.0 script through the "narrative" event and condition. */
final class QuestScriptsTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private final NarrativeTestKit kit = new NarrativeTestKit();
    private final List<String> problems = new ArrayList<>();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private static EventDefinition event(String json) {
        EventDefinition event = new EventDefinition();
        JsonNode node = parse(json);
        event.setType(node.path("type").asText());
        node.properties().forEach(entry -> event.put(entry.getKey(), entry.getValue()));
        return event;
    }

    private static ConditionDefinition condition(String json) {
        ConditionDefinition condition = new ConditionDefinition();
        JsonNode node = parse(json);
        condition.setType(node.path("type").asText());
        node.properties().forEach(entry -> condition.put(entry.getKey(), entry.getValue()));
        return condition;
    }

    @Test
    void anEventRunsItsActionsInTheStorySessionEveryTimeItFires() {
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:herbs.found", "type": "integer", "scope": "player", "default": 0 } ],
                  "tagSchemas": [ { "id": "hyzion:herbs.started", "scope": "quest_session" } ] }
                """);
        QuestScripts scripts = new QuestScripts(kit.runtime(), problems::add);
        EventDefinition start = event("""
                { "type": "narrative", "story": "hyzion:herbs", "actions": [
                    { "type": "mysticquests:variable.increment", "variable": "hyzion:herbs.found" },
                    { "type": "mysticquests:tag.add", "tag": "hyzion:herbs.started" } ] }
                """);
        scripts.run(ALICE, "greenvale", start, QuestTargetContext.none());
        scripts.run(ALICE, "greenvale", start, QuestTargetContext.none());

        assertEquals(new QuestValue.IntValue(2),
                kit.runtime().variables().get(ScopeContext.player(ALICE), null, id("hyzion:herbs.found")).orElseThrow(),
                "v1 events run every time they fire");
        ConditionDefinition started = condition("""
                { "type": "narrative", "story": "hyzion:herbs", "condition": { "type": "tag", "tag": "hyzion:herbs.started" } }
                """);
        assertTrue(scripts.test(ALICE, "greenvale", started), "the tag lives in Alice's story session");
        assertFalse(scripts.test(BOB, "greenvale", started), "and checking it never opens a session for Bob");
        assertTrue(kit.runtime().sessions().active(SessionOwner.player(BOB), "hyzion:herbs").isEmpty());
        assertTrue(problems.isEmpty(), problems.toString());
    }

    @Test
    void scriptsAreCheckedAtReloadWhereverTheyAreNested() {
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:herbs.found", "type": "integer", "scope": "player" } ] }
                """);
        QuestDefinition quest = new QuestDefinition();
        quest.setId("herbs");
        quest.setPackageId("greenvale");
        quest.setStartConditions(List.of(condition("""
                { "type": "narrative", "condition": { "type": "variable", "variable": "hyzion:herbs.missing", "op": ">=", "value": 1 } }
                """)));
        EventDefinition nested = event("""
                { "type": "if", "conditions": [ { "type": "tag", "tag": "x" } ],
                  "then": [ { "type": "narrative", "actions": [ { "type": "mysticquests:tag.ad", "tag": "hyzion:herbs.done" } ] } ] }
                """);
        quest.setRewards(List.of(nested));
        LoadedContent content = new LoadedContent(Set.of("greenvale"), Map.of("greenvale:herbs", quest), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());

        DiagnosticReport report = new DiagnosticReport();
        QuestScripts.validate(content, kit.runtime().content(), kit.runtime()::compileContext, report);

        assertTrue(report.errors().stream().anyMatch(error -> error.path().contains("startConditions")
                && error.message().contains("hyzion:herbs.missing")), report.format());
        assertTrue(report.errors().stream().anyMatch(error -> error.path().contains("rewards[0].then[0]")
                && error.message().contains("mysticquests:tag.ad")), report.format());
    }

    /** The documented examples, including the nested sealed_grove story, must load and pass script validation. */
    @Test
    void theExamplePackagesLoadWithTheirScripts() throws IOException {
        LegacyBridge.register(kit.runtime(), () -> null, new MysticQuestsRegistry(null), player -> true);
        LoadedContent loaded = new QuestContentLoader(Json.createMapper()).load(Path.of("examples/packages"));
        assertTrue(loaded.packages().containsAll(Set.of("sealed_grove", "sealed_grove/chapter_1", "sealed_grove/chapter_2")),
                loaded.packages().toString());
        DiagnosticReport report = new DiagnosticReport();
        NarrativeContent compiled = kit.runtime().compile(loaded.narrativeSections(), Map.of(), report);
        QuestScripts.validate(loaded, compiled, kit.runtime()::compileContext, report);
        assertFalse(report.hasErrors(), report.format());
        assertTrue(compiled.puzzles().containsKey(id("grove:runes")));
        assertTrue(compiled.cutscenes().containsKey(id("grove:seal_opening")));
    }
}
