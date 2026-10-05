package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.Diagnostic;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink.Placement;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService.PlayReport;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §13, §18 and §26: story audio reaches only its audience, in the listener's language, in order, and
 * story music is resolved per listener from persisted state.
 */
final class QuestMediaTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-4000-8000-00000000000c");

    private static final String MEDIA = """
            {
              "speakers": [
                { "id": "hyzion:old_man", "name": "Old Man" },
                { "id": "hyzion:narrator", "type": "narrator", "nameKey": "speaker.narrator" } ],
              "media": [
                { "id": "hyzion:old_man.warning", "kind": "voice", "speaker": "hyzion:old_man", "duration": 4,
                  "sounds": { "en-US": "VO_Warning_EN", "fr-FR": "VO_Warning_FR" }, "subtitleKey": "dialogue.old_man.warning" },
                { "id": "hyzion:old_man.again", "kind": "voice", "speaker": "hyzion:old_man", "duration": 2,
                  "interruption": "replace_same_speaker", "sound": "VO_Again", "subtitle": "Again!" },
                { "id": "hyzion:narrator.intro", "kind": "voice", "speaker": "hyzion:narrator", "duration": 3,
                  "sound": "VO_Intro", "subtitle": "Long ago..." },
                { "id": "hyzion:ghost.whisper", "kind": "voice", "sound": "Missing_Sound", "subtitle": "...leave...", "duration": 1 },
                { "id": "hyzion:temple.rumble", "kind": "sfx", "sound": "SFX_Rumble", "spatial": "position" },
                { "id": "hyzion:temple.tension", "kind": "music", "music": "Music_Temple_Tension" },
                { "id": "hyzion:temple.calm", "kind": "music", "music": "Music_Temple_Calm" } ],
              "puzzles": [ {
                "id": "hyzion:druid_temple.lever", "story": "hyzion:druid_temple", "audience": "auto",
                "inputs": ["lever"], "rule": "any",
                "outputs": [
                  { "type": "mysticquests:media.play", "media": "hyzion:temple.rumble", "at": { "x": 1, "y": 2, "z": 3 } },
                  { "type": "mysticquests:music.set", "music": "hyzion:temple.tension" } ]
              } ]
            }
            """;

    private final NarrativeTestKit kit = new NarrativeTestKit();
    private final FakeSink sink = new FakeSink();

    @BeforeEach
    void setUp() {
        sink.online.addAll(List.of(ALICE, BOB, CAROL));
        kit.runtime().media().bind(sink, sink, "en-US");
        kit.load(MEDIA);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private QuestMediaService media() {
        return kit.runtime().media();
    }

    private MediaAsset asset(String raw) {
        return media().asset(id(raw));
    }

    @Test
    void twoPlayersInOneRoomHearOnlyTheirOwnStory() {
        kit.parties.put(ALICE, "p1");
        kit.parties.put(CAROL, "p1");
        assertEquals(Outcome.COMPLETED, kit.runtime().puzzles().input(ALICE, null, id("hyzion:druid_temple.lever"), "lever", true).outcome());

        assertEquals(Set.of(ALICE, CAROL), sink.listenersOf("SFX_Rumble"), "the party's session hears its rumble");
        assertFalse(sink.listenersOf("SFX_Rumble").contains(BOB), "Bob, standing beside them, hears nothing");
        assertEquals(Spatial.POSITION, sink.played.getFirst().cue().placement().spatial());

        assertEquals("Music_Temple_Tension", media().music(ALICE).container());
        assertEquals("Music_Temple_Tension", media().music(CAROL).container());
        assertNull(media().music(BOB).container(), "Bob keeps the world's own music");
        assertEquals(TriggerScope.STORY_SESSION, media().music(ALICE).decidedBy());
    }

    @Test
    void voiceLinesQueuePerListenerAndSameSpeakerLinesReplace() {
        MediaAsset warning = asset("hyzion:old_man.warning");
        MediaAsset intro = asset("hyzion:narrator.intro");
        assertEquals(new PlayReport(1, 0, 0, 0), media().play(List.of(ALICE), warning, Placement.HEAD, true));
        assertEquals(new PlayReport(0, 1, 0, 0), media().play(List.of(ALICE), intro, Placement.HEAD, true),
                "the narrator waits for the old man to finish");
        assertEquals(new PlayReport(1, 0, 0, 0), media().play(List.of(BOB), intro, Placement.HEAD, true),
                "Bob's channel is his own");

        kit.clock.advance(Duration.ofSeconds(3));
        media().tick();
        assertEquals(0, sink.count(ALICE, "VO_Intro"), "still waiting");
        kit.clock.advance(Duration.ofSeconds(1));
        media().tick();
        assertEquals(1, sink.count(ALICE, "VO_Intro"), "the queued line starts on time");

        MediaAsset again = asset("hyzion:old_man.again");
        media().play(List.of(BOB), intro, Placement.HEAD, false);
        assertEquals(new PlayReport(0, 1, 0, 0), media().play(List.of(BOB), again, Placement.HEAD, false),
                "a different speaker is mid-line, so the old man queues");
        media().play(List.of(CAROL), warning, Placement.HEAD, false);
        assertEquals(new PlayReport(1, 0, 0, 0), media().play(List.of(CAROL), again, Placement.HEAD, false),
                "the same speaker cuts his own line short");
    }

    @Test
    void listenersHearTheirLanguageWithADiagnosedFallback() {
        sink.locales.put(ALICE, "fr-CA");
        sink.locales.put(BOB, "de-DE");
        MediaAsset warning = asset("hyzion:old_man.warning");
        media().play(List.of(ALICE, BOB, CAROL), warning, Placement.HEAD, true);

        assertEquals(1, sink.count(ALICE, "VO_Warning_FR"), "fr-CA hears the French recording");
        assertEquals(1, sink.count(BOB, "VO_Warning_EN"), "German falls back to the fallback locale");
        assertEquals(1, sink.count(CAROL, "VO_Warning_EN"), "an unknown language hears the fallback without fuss");
        assertEquals(1, kit.problems.stream().filter(problem -> problem.contains("de-DE")).count(), kit.problems.toString());
        media().stop(List.of(ALICE, BOB, CAROL), null);
        media().play(List.of(BOB), warning, Placement.HEAD, true);
        assertEquals(1, kit.problems.stream().filter(problem -> problem.contains("de-DE")).count(), "reported once, not per line");
        assertEquals("dialogue.old_man.warning", sink.subtitles.get(ALICE).getFirst().key(), "subtitles follow the reader's language");
        assertEquals("Old Man", sink.subtitles.get(ALICE).getFirst().speaker().name());
    }

    @Test
    void playersChooseTheirVoiceLanguageAndSubtitlesAndKeepThem() throws Exception {
        sink.locales.put(ALICE, "en-US");
        media().setVoiceLocale(ALICE, "fr-FR");
        media().setSubtitles(BOB, false);
        MediaAsset warning = asset("hyzion:old_man.warning");
        media().play(List.of(ALICE, BOB), warning, Placement.HEAD, true);

        assertEquals(1, sink.count(ALICE, "VO_Warning_FR"), "Alice hears French although her game is in English");
        assertEquals("dialogue.old_man.warning", sink.subtitles.get(ALICE).getFirst().key(),
                "and reads the subtitle key, which her client shows in English");
        assertEquals(1, sink.count(BOB, "VO_Warning_EN"), "Bob still hears the line");
        assertNull(sink.subtitles.get(BOB), "but sees no subtitle");

        kit.runtime().flush();
        kit.restart();
        assertEquals(new QuestMediaService.Preferences("fr-FR", true), kit.runtime().media().preferences(ALICE),
                "preferences are saved with the player");
        assertFalse(kit.runtime().media().preferences(BOB).subtitles());
        kit.runtime().media().setVoiceLocale(ALICE, null);
        assertNull(kit.runtime().media().preferences(ALICE).voiceLocale(), "auto follows the game language again");
    }

    @Test
    void aMissingVoiceLineStillShowsItsSubtitle() {
        PlayReport report = media().play(List.of(ALICE), asset("hyzion:ghost.whisper"), Placement.HEAD, true);
        assertEquals(1, report.started(), "the line counts as delivered through its subtitle");
        assertEquals("...leave...", sink.subtitles.get(ALICE).getFirst().text());
        assertTrue(kit.problems.stream().anyMatch(problem -> problem.contains("Missing_Sound")), kit.problems.toString());
    }

    @Test
    void musicResolvesPerListenerAndSurvivesRestart() {
        media().setMusic(TriggerScope.GLOBAL, "hyzion:temple.calm", ScopeContext.none());
        media().setMusic(TriggerScope.PLAYER, "hyzion:temple.tension", ScopeContext.player(ALICE));
        media().setMusic(TriggerScope.PLAYER, QuestMediaService.NO_MUSIC, ScopeContext.player(BOB));
        assertEquals("Music_Temple_Tension", media().music(ALICE).container());
        assertNull(media().music(BOB).container(), "\"none\" lets the world's music play for Bob");
        assertEquals(TriggerScope.PLAYER, media().music(BOB).decidedBy());
        assertEquals("Music_Temple_Calm", media().music(CAROL).container());

        kit.runtime().flush();
        kit.restart();
        assertEquals("Music_Temple_Tension", kit.runtime().media().music(ALICE).container(), "rebuilt from state, not memory");
        kit.runtime().media().setMusic(TriggerScope.PLAYER, null, ScopeContext.player(ALICE));
        assertEquals("Music_Temple_Calm", kit.runtime().media().music(ALICE).container(), "clearing falls back to the next level");
    }

    @Test
    void brokenMediaFailsTheReloadAndMissingAssetsWarn() {
        sink.missing.add("SFX_Not_Loaded");
        DiagnosticReport report = kit.compile("""
                {
                  "media": [
                    { "id": "hyzion:duck", "sound": "SFX_A", "channel": "voice", "interruption": "duck_existing" },
                    { "id": "hyzion:german_only", "kind": "voice", "sounds": { "de-DE": "VO_DE" } },
                    { "id": "hyzion:mystery", "kind": "voice", "sound": "VO_X", "speaker": "hyzion:nobody" },
                    { "id": "hyzion:unloaded", "sound": "SFX_Not_Loaded" },
                    { "id": "hyzion:boom", "sound": "SFX_Boom", "spatial": "position" } ],
                  "puzzles": [ { "id": "hyzion:p", "inputs": ["x"], "rule": "any", "outputs": [
                    { "type": "mysticquests:media.play", "media": "hyzion:missing" },
                    { "type": "mysticquests:media.play", "media": "hyzion:boom" },
                    { "type": "mysticquests:music.set", "music": "hyzion:unloaded" } ] } ]
                }
                """);
        assertTrue(messages(report.errors()).contains("ducking"), report.format());
        assertTrue(messages(report.errors()).contains("fallback locale en-US"), report.format());
        assertTrue(messages(report.errors()).contains("unknown speaker"), report.format());
        assertTrue(messages(report.errors()).contains("unknown media 'hyzion:missing'"), report.format());
        assertTrue(messages(report.errors()).contains("is positional"), report.format());
        assertTrue(messages(report.errors()).contains("unknown music 'hyzion:unloaded'"), report.format());
        assertTrue(report.warnings().stream().anyMatch(warning -> warning.code() == DiagnosticCode.MISSING_ASSET
                && warning.message().contains("SFX_Not_Loaded")), "an unloaded sound warns but does not block");
    }

    private static String messages(List<Diagnostic> diagnostics) {
        StringBuilder all = new StringBuilder();
        diagnostics.forEach(diagnostic -> all.append(diagnostic.message()).append(' ').append(diagnostic.hint()).append('\n'));
        return all.toString();
    }

    /** Records what each listener was sent. */
    private static final class FakeSink implements MediaSink, MediaCatalog {
        record Played(UUID listener, Cue cue) {
        }

        final Set<UUID> online = new LinkedHashSet<>();
        final Map<UUID, String> locales = new HashMap<>();
        final Set<String> missing = new LinkedHashSet<>(Set.of("Missing_Sound"));
        final List<Played> played = new ArrayList<>();
        final Map<UUID, List<Subtitle>> subtitles = new HashMap<>();

        Set<UUID> listenersOf(String sound) {
            Set<UUID> listeners = new LinkedHashSet<>();
            played.stream().filter(entry -> entry.cue().sound().equals(sound)).forEach(entry -> listeners.add(entry.listener()));
            return listeners;
        }

        long count(UUID listener, String sound) {
            return played.stream().filter(entry -> entry.listener().equals(listener) && entry.cue().sound().equals(sound)).count();
        }

        @Override
        public Collection<UUID> online() {
            return online;
        }

        @Override
        public String worldOf(UUID player) {
            return "world";
        }

        @Override
        public String locale(UUID player) {
            return locales.get(player);
        }

        @Override
        public boolean play(UUID listener, Cue cue) {
            if (missing.contains(cue.sound())) {
                return false;
            }
            played.add(new Played(listener, cue));
            return true;
        }

        @Override
        public void subtitle(UUID listener, Subtitle subtitle) {
            subtitles.computeIfAbsent(listener, ignored -> new ArrayList<>()).add(subtitle);
        }

        @Override
        public Boolean soundExists(String soundEvent) {
            return !missing.contains(soundEvent);
        }

        @Override
        public Boolean musicExists(String musicContainer) {
            return true;
        }
    }
}
