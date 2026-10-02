package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.integration.narrative.LegacyBridge;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.util.Json;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** How narrative sections travel from package files into a compiled release, and what fails it. */
final class NarrativeContentTest {
    private final NarrativeTestKit kit = new NarrativeTestKit();

    @TempDir
    Path packages;

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void schemasDeclaredInOnePackageAreVisibleToPuzzlesInAnother() {
        kit.loadPackages(Map.of(
                "zz_shared", """
                        { "tagSchemas": [ { "id": "hyzion:shared.flag", "scope": "quest_session" } ] }
                        """,
                "aa_temple", """
                        { "puzzles": [ { "id": "hyzion:temple", "inputs": ["a"], "rule": "all",
                          "outputs": [ { "type": "mysticquests:tag.add", "tag": "hyzion:shared.flag" } ] } ] }
                        """));
        assertEquals(1, kit.runtime().content().puzzles().size());
        assertEquals("aa_temple", kit.runtime().content().puzzles().values().iterator().next().packageId());
    }

    @Test
    void duplicatesAcrossPackagesFailTheReload() {
        DiagnosticReport report = new DiagnosticReport();
        kit.runtime().compile(Map.of(
                "a", NarrativeTestKit.parse("{ \"tagSchemas\": [ { \"id\": \"hyzion:x\" } ] }"),
                "b", NarrativeTestKit.parse("{ \"tagSchemas\": [ { \"id\": \"hyzion:x\" } ] }")), Map.of(), report);
        assertTrue(report.has(DiagnosticCode.DUPLICATE_ID));
    }

    @Test
    void aFailedReloadKeepsThePreviousRelease() {
        kit.load("{ \"puzzles\": [ { \"id\": \"hyzion:old\", \"inputs\": [\"a\"], \"rule\": \"any\", \"outputs\": [] } ] }");
        DiagnosticReport report = kit.runtime().reload(
                Map.of("test", NarrativeTestKit.parse("{ \"puzzles\": [ { \"id\": \"hyzion:new\", \"inputs\": [], \"rule\": \"all\" } ] }")),
                Map.of());
        assertTrue(report.hasErrors());
        assertTrue(kit.runtime().content().puzzles().containsKey(NarrativeTestKit.id("hyzion:old")));
    }

    @Test
    void theV1LoaderCarriesNarrativeSectionsAndSignalObjectives() throws IOException {
        Path temple = Files.createDirectories(packages.resolve("temple"));
        Files.writeString(temple.resolve("quests.json"), """
                { "quests": [ { "id": "keys", "objectives": [
                    { "id": "collect", "type": "signal", "signal": "hyzion:druid_temple.keys_complete" } ] } ] }
                """);
        Files.writeString(temple.resolve("puzzles.json"), """
                [ { "id": "hyzion:p", "inputs": ["a"], "rule": "all" } ]
                """);
        Files.writeString(temple.resolve("schemas.yml"), """
                tagSchemas:
                  - id: hyzion:flag
                    scope: player
                """);
        LoadedContent loaded = new QuestContentLoader(Json.createMapper()).load(packages);
        assertTrue(loaded.narrativeSections().get("temple").has("puzzles"));
        assertTrue(loaded.narrativeSections().get("temple").has("tagSchemas"));

        Files.writeString(temple.resolve("quests.json"), """
                { "quests": [ { "id": "keys", "objectives": [ { "id": "collect", "type": "signal" } ] } ] }
                """);
        IOException failure = assertThrows(IOException.class, () -> new QuestContentLoader(Json.createMapper()).load(packages));
        assertTrue(failure.getMessage().contains("names no \"signal\""), failure.getMessage());
    }

    /** The documented example must stay loadable by both loaders, with the real v1 bridge validating it. */
    @Test
    void theDruidTempleExampleCompiles() throws IOException {
        LegacyBridge.register(kit.runtime(), () -> null, new MysticQuestsRegistry(null), player -> true);
        LoadedContent loaded = new QuestContentLoader(Json.createMapper()).load(Path.of("examples/packages"));
        DiagnosticReport report = new DiagnosticReport();
        NarrativeContent compiled = kit.runtime().compile(loaded.narrativeSections(), Map.of(), report);
        assertFalse(report.hasErrors(), report.format());
        NarrativeContent.PuzzleBinding binding = compiled.bindings("avalon:druid_key_7").getFirst();
        assertEquals("key_7", binding.input());
        assertEquals(4, compiled.puzzles().get(NarrativeTestKit.id("hyzion:druid_temple.hidden_keys")).selection().active());
        assertTrue(loaded.quests().containsKey("druid_temple:hidden_keys"));

        DiagnosticReport typo = new DiagnosticReport();
        kit.runtime().compile(Map.of("bad", NarrativeTestKit.parse("""
                { "puzzles": [ { "id": "hyzion:bad", "inputs": ["a"], "rule": "all",
                  "requires": { "type": "questActiv", "quest": "x" },
                  "outputs": [ { "type": "sendTitel", "title": "x" } ] } ] }
                """)), Map.of(), typo);
        assertTrue(typo.has(DiagnosticCode.UNKNOWN_CONDITION), "misspelled v1 condition: " + typo.format());
        assertTrue(typo.has(DiagnosticCode.UNKNOWN_ACTION), "misspelled v1 event");
    }

    @Test
    void interventionsAreAuditedToAnAppendOnlyCollection() throws IOException {
        kit.runtime().audit().record(UUID.randomUUID().toString(), "puzzle.reset", "player-1", "qs-1",
                "round 0", "round 1", "stuck after a crash");
        kit.runtime().audit().record("console", "trigger.disable", "player-1", null, "enabled", "disabled", null);
        List<String> entries = kit.store.list("audit");
        assertEquals(2, entries.size());
        assertEquals("stuck after a crash", kit.store.read("audit", entries.getFirst()).orElseThrow().get("reason").asText());
        assertEquals(2, kit.audits.size());
    }
}
