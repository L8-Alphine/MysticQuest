package org.hyzionstudios.mysticquests.narrative.cutscene;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.cutscene.QuestCutsceneService.Outcome;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.media.MediaCatalog;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §17 and §26.3 "Cutscene skip": however a scene ends, required state, cleanup and progression are
 * the same, and nothing runs twice.
 */
final class CutsceneTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static final String TEMPLE = """
            {
              "variableSchemas": [
                { "id": "hyzion:temple.awakenings", "type": "integer", "scope": "player", "default": 0 },
                { "id": "hyzion:temple.phase", "type": "string", "scope": "player", "default": "asleep" } ],
              "media": [ { "id": "hyzion:temple.chant", "kind": "voice", "sound": "VO_Chant", "duration": 3 } ],
              "cutscenes": [ {
                "id": "hyzion:temple.awakening", "story": "hyzion:druid_temple",
                "steps": [
                  { "at": 0, "type": "mysticquests:variable.increment", "variable": "hyzion:temple.awakenings" },
                  { "at": 2, "type": "mysticquests:media.play", "media": "hyzion:temple.chant", "cosmetic": true },
                  { "at": 5, "type": "mysticquests:variable.set", "variable": "hyzion:temple.phase", "value": "awake" } ],
                "onEnd": [ { "type": "mysticquests:signal", "signal": "hyzion:temple.awakened" } ]
              }, {
                "id": "hyzion:temple.vision", "story": "hyzion:druid_temple", "skippable": false,
                "steps": [ { "at": 4, "type": "mysticquests:variable.set", "variable": "hyzion:temple.phase", "value": "seen" } ]
              } ],
              "puzzles": [ {
                "id": "hyzion:temple.altar", "story": "hyzion:druid_temple", "inputs": ["touch"], "rule": "any",
                "outputs": [ { "type": "mysticquests:cutscene.play", "cutscene": "hyzion:temple.awakening" } ]
              } ]
            }
            """;

    private final NarrativeTestKit kit = new NarrativeTestKit();
    private final List<String> heard = new ArrayList<>();

    @BeforeEach
    void setUp() {
        bindSink();
        kit.load(TEMPLE);
    }

    private void bindSink() {
        kit.runtime().media().bind(new MediaSink() {
            @Override
            public Collection<UUID> online() {
                return Set.of(ALICE);
            }

            @Override
            public String worldOf(UUID player) {
                return "world";
            }

            @Override
            public String locale(UUID player) {
                return null;
            }

            @Override
            public boolean play(UUID listener, Cue cue) {
                heard.add(cue.sound());
                return true;
            }

            @Override
            public void subtitle(UUID listener, Subtitle subtitle) {
            }
        }, MediaCatalog.UNKNOWN, "en-US");
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private QuestCutsceneService cutscenes() {
        return kit.runtime().cutscenes();
    }

    private QuestValue variable(String raw) {
        return kit.runtime().variables().get(ScopeContext.player(ALICE), null, id(raw)).orElseThrow();
    }

    private long awakenedSignals() {
        return kit.signals.stream().filter(signal -> signal.contains("hyzion:temple.awakened")).count();
    }

    @Test
    void aSceneStartedByAPuzzlePlaysOutOnSchedule() {
        assertEquals(QuestPuzzleService.Outcome.COMPLETED,
                kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.altar"), "touch", true).outcome());
        assertEquals(new QuestValue.IntValue(1), variable("hyzion:temple.awakenings"), "the step at 0s ran at once");
        assertEquals(Set.of(ALICE), cutscenes().drivers());

        kit.clock.advance(Duration.ofSeconds(2));
        cutscenes().advance(ALICE);
        assertEquals(List.of("VO_Chant"), heard);
        assertEquals(new QuestValue.StringValue("asleep"), variable("hyzion:temple.phase"), "not yet");

        kit.clock.advance(Duration.ofSeconds(3));
        cutscenes().advance(ALICE);
        assertEquals(new QuestValue.StringValue("awake"), variable("hyzion:temple.phase"));
        assertEquals(1, awakenedSignals(), "onEnd ran once");
        assertTrue(cutscenes().drivers().isEmpty());
        assertTrue(cutscenes().describe(ALICE).isEmpty(), "the finished scene is gone from the session");
    }

    @Test
    void skippingRunsRequiredStepsDropsCosmeticOnesAndFinalizes() {
        assertEquals(Outcome.STARTED, cutscenes().play(ALICE, id("hyzion:temple.awakening"), null));
        kit.clock.advance(Duration.ofSeconds(1));
        assertEquals(Outcome.SKIPPED, cutscenes().skip(ALICE, false));

        assertTrue(heard.isEmpty(), "the cosmetic chant is dropped");
        assertEquals(new QuestValue.StringValue("awake"), variable("hyzion:temple.phase"), "the required step at 5s still ran");
        assertEquals(new QuestValue.IntValue(1), variable("hyzion:temple.awakenings"), "and the one at 0s did not run again");
        assertEquals(1, awakenedSignals());
        assertEquals(Outcome.NOT_RUNNING, cutscenes().skip(ALICE, false));
    }

    @Test
    void anUnskippableSceneYieldsOnlyToStaff() {
        cutscenes().play(ALICE, id("hyzion:temple.vision"), null);
        assertEquals(Outcome.NOT_SKIPPABLE, cutscenes().skip(ALICE, false));
        assertEquals(Outcome.SKIPPED, cutscenes().skip(ALICE, true));
        assertEquals(new QuestValue.StringValue("seen"), variable("hyzion:temple.phase"));
    }

    @Test
    void aSceneInterruptedByARestartIsFinishedWhenThePlayerReturns() {
        cutscenes().play(ALICE, id("hyzion:temple.awakening"), null);
        kit.runtime().flush();
        kit.restart();
        bindSink();
        assertEquals(1, kit.runtime().cutscenes().describe(ALICE).size(), "the run was saved with the session");

        kit.runtime().onJoin(ALICE);
        assertTrue(kit.runtime().cutscenes().describe(ALICE).isEmpty());
        assertEquals(new QuestValue.StringValue("awake"), variable("hyzion:temple.phase"));
        assertEquals(new QuestValue.IntValue(1), variable("hyzion:temple.awakenings"), "no step ran twice across the restart");
        assertEquals(1, awakenedSignals());
        assertTrue(heard.isEmpty(), "recovery does not replay the cosmetic chant");
    }

    @Test
    void startingAnotherSceneFinishesTheFirstAndEachPlayRunsAgain() {
        cutscenes().play(ALICE, id("hyzion:temple.awakening"), null);
        assertEquals(Outcome.STARTED, cutscenes().play(ALICE, id("hyzion:temple.awakening"), null));
        assertEquals(1, awakenedSignals(), "the replaced scene was finalized");
        assertEquals(new QuestValue.IntValue(2), variable("hyzion:temple.awakenings"), "a new play is a new run");
    }

    @Test
    void theSkipContractIsCheckedAtLoad() {
        kit.runtime().actionTypes().registerBuiltIn(ActionCompiler.LEGACY_EVENT, (context, parameters) -> ActionResult.success());
        DiagnosticReport report = kit.compile("""
                {
                  "variableSchemas": [ { "id": "hyzion:x", "type": "integer", "scope": "player" } ],
                  "media": [ { "id": "hyzion:theme", "kind": "music", "music": "MC_Theme" } ],
                  "cutscenes": [
                    { "id": "hyzion:camera", "steps": [ { "type": "setCamera", "mode": "third", "locked": true } ] },
                    { "id": "hyzion:cosmetic_state", "steps": [
                      { "type": "mysticquests:variable.set", "variable": "hyzion:x", "value": 1, "cosmetic": true } ] },
                    { "id": "hyzion:music", "steps": [ { "type": "mysticquests:music.set", "music": "hyzion:theme" } ] },
                    { "id": "hyzion:fine", "steps": [ { "type": "setCamera", "mode": "third" } ],
                      "onEnd": [ { "type": "setCamera", "mode": "first" } ] } ],
                  "puzzles": [ { "id": "hyzion:p", "inputs": ["x"], "rule": "any",
                    "outputs": [ { "type": "mysticquests:cutscene.play", "cutscene": "hyzion:missing" } ] } ]
                }
                """);
        assertTrue(report.errors().stream().anyMatch(error -> error.code() == DiagnosticCode.CUTSCENE_NO_RECOVERY
                && error.path().contains("hyzion:camera")), report.format());
        assertTrue(report.errors().stream().anyMatch(error -> error.message().contains("cannot be cosmetic")), report.format());
        assertTrue(report.errors().stream().anyMatch(error -> error.message().contains("unknown cutscene 'hyzion:missing'")), report.format());
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.code() == DiagnosticCode.UNTERMINATED_MEDIA), report.format());
        assertFalse(report.errors().stream().anyMatch(error -> error.path().contains("hyzion:fine")), report.format());
    }
}
