package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.TagRecord;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Part;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The story-state half of {@link PlayerStateAdmin}: what a clear removes and keeps, typed story
 * variables, and which sessions a clear may touch. The v1 half delegates to
 * {@link PlayerQuestService} and {@link ScopedStateService}, which have their own coverage.
 */
final class PlayerStateAdminTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final String STAFF = "00000000-0000-0000-0000-0000000057af";

    private NarrativeTestKit kit;
    private final List<String> audit = new ArrayList<>();
    private boolean online;

    @BeforeEach
    void setUp() {
        kit = new NarrativeTestKit();
        kit.load("""
                {
                  "tagSchemas": [
                    { "id": "hyzion:avalon.discovered", "scope": "player" },
                    { "id": "hyzion:party.ready", "scope": "party" }
                  ],
                  "variableSchemas": [
                    { "id": "hyzion:keys_found", "type": "integer", "scope": "player", "default": 0 },
                    { "id": "hyzion:phase", "type": "enum", "values": ["idle", "awake"], "scope": "server" }
                  ]
                }
                """);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private PlayerStateAdmin admin() {
        return new PlayerStateAdmin(null, null, () -> kit.runtime(),
                (actor, action, player, session, before, after, reason) -> audit.add(action + " " + reason),
                player -> Optional.of("Alice"), player -> online);
    }

    private OwnerState player() {
        return kit.runtime().store().state(ScopeOwner.player(ALICE));
    }

    private void seedProgressAndPreferences() {
        player().putVariable(id("hyzion:keys_found"), new QuestValue.IntValue(3));
        player().putTag(new TagRecord(id("hyzion:avalon.discovered"), kit.clock.instant(), null, "test"), kit.clock.instant());
        player().putVariable(id("mysticquests:ui.tracker"), new QuestValue.StringValue("compact"));
        player().putVariable(id("mysticquests:media.subtitles"), new QuestValue.BoolValue(false));
        kit.runtime().store().state(ScopeOwner.quest(ALICE, "test:intro"))
                .putVariable(id("hyzion:keys_found"), new QuestValue.IntValue(1));
        kit.runtime().flush();
    }

    @Test
    void clearingStoryStateKeepsTheirSettingsAndSurvivesARestart() throws IOException {
        seedProgressAndPreferences();

        Outcome outcome = admin().clear(STAFF, ALICE, EnumSet.of(Part.STORY_STATE), "testing the intro again");

        assertTrue(outcome.ok(), outcome.message());
        kit.restart();
        OwnerState stored = kit.runtime().store().peek(ScopeOwner.player(ALICE));
        assertNull(stored.variable(id("hyzion:keys_found")));
        assertTrue(stored.liveTags(kit.clock.instant()).isEmpty());
        assertEquals(new QuestValue.StringValue("compact"), stored.variable(id("mysticquests:ui.tracker")));
        assertEquals(new QuestValue.BoolValue(false), stored.variable(id("mysticquests:media.subtitles")));
        assertTrue(kit.runtime().store().peek(ScopeOwner.quest(ALICE, "test:intro")).isEmpty(),
                "Per-quest story state belongs to the player and is cleared with it");
        assertTrue(audit.stream().allMatch(line -> line.startsWith("player.clear.story") && line.endsWith("testing the intro again")));
    }

    @Test
    void clearingSettingsAloneKeepsStoryProgress() throws IOException {
        seedProgressAndPreferences();

        Outcome outcome = admin().clear(STAFF, ALICE, EnumSet.of(Part.PREFERENCES), "reset their HUD");

        assertTrue(outcome.ok(), outcome.message());
        OwnerState stored = kit.runtime().store().peek(ScopeOwner.player(ALICE));
        assertNull(stored.variable(id("mysticquests:ui.tracker")));
        assertNull(stored.variable(id("mysticquests:media.subtitles")));
        assertEquals(new QuestValue.IntValue(3), stored.variable(id("hyzion:keys_found")));
        assertEquals(1, stored.liveTags(kit.clock.instant()).size());
    }

    @Test
    void clearingBothEmptiesThePlayersOwnState() throws IOException {
        seedProgressAndPreferences();

        Outcome outcome = admin().clear(STAFF, ALICE, EnumSet.of(Part.STORY_STATE, Part.PREFERENCES), "full wipe");

        assertTrue(outcome.ok(), outcome.message());
        assertTrue(kit.runtime().store().peek(ScopeOwner.player(ALICE)).isEmpty());
    }

    @Test
    void everyChangeNeedsAReason() {
        seedProgressAndPreferences();

        Outcome cleared = admin().clear(STAFF, ALICE, EnumSet.of(Part.STORY_STATE), "  ");
        Outcome set = admin().setStoryVariable(STAFF, ALICE, "hyzion:keys_found", "4", "");

        assertFalse(cleared.ok());
        assertFalse(set.ok());
        assertEquals(new QuestValue.IntValue(3), player().variable(id("hyzion:keys_found")));
        assertTrue(audit.isEmpty());
    }

    @Test
    void storyVariablesAreTypedAndOnlyThePlayersOwn() {
        PlayerStateAdmin admin = admin();

        assertTrue(admin.setStoryVariable(STAFF, ALICE, "hyzion:keys_found", "5", "support ticket").ok());
        assertEquals(new QuestValue.IntValue(5), player().variable(id("hyzion:keys_found")));

        assertFalse(admin.setStoryVariable(STAFF, ALICE, "hyzion:keys_found", "five", "support ticket").ok(),
                "A value that does not fit the declared type is refused");
        assertFalse(admin.setStoryVariable(STAFF, ALICE, "hyzion:undeclared", "1", "support ticket").ok(),
                "Undeclared story variables are refused rather than stored untyped");
        assertFalse(admin.setStoryVariable(STAFF, ALICE, "hyzion:phase", "awake", "support ticket").ok(),
                "A server-scoped variable is not one player's to set");
        assertFalse(admin.setStoryVariable(STAFF, ALICE, "mysticquests:ui.tracker", "hidden", "support ticket").ok(),
                "Settings are the player's own, changed in their Journal");
        assertEquals(new QuestValue.IntValue(5), player().variable(id("hyzion:keys_found")));
    }

    @Test
    void storyTagsMustBeDeclaredForOnePlayer() {
        PlayerStateAdmin admin = admin();

        assertTrue(admin.addStoryTag(STAFF, ALICE, "hyzion:avalon.discovered", "support").ok());
        assertFalse(admin.addStoryTag(STAFF, ALICE, "hyzion:party.ready", "support").ok());
        assertTrue(admin.removeStoryTag(STAFF, ALICE, ScopeOwner.player(ALICE).key(), "hyzion:avalon.discovered", "support").ok());
        assertFalse(admin.removeStoryTag(STAFF, ALICE, "server/test-server", "hyzion:avalon.discovered", "support").ok(),
                "State outside the player's own owners cannot be reached through them");
    }

    @Test
    void clearingSessionsAbandonsTheirOwnStoriesButNeverAPartys() {
        kit.parties.put(ALICE, "party-1");
        QuestSession own = kit.runtime().sessions().open(SessionOwner.player(ALICE), "test:solo", "v1");
        QuestSession shared = kit.runtime().sessions().open(SessionOwner.party("party-1"), "test:raid", "v1");

        Outcome outcome = admin().clear(STAFF, ALICE, EnumSet.of(Part.SESSIONS), "stuck story");

        assertTrue(outcome.ok(), outcome.message());
        assertFalse(kit.runtime().sessions().get(own.id()).map(QuestSession::active).orElse(true));
        assertTrue(kit.runtime().sessions().get(shared.id()).map(QuestSession::active).orElse(false),
                "A party story is shared; clearing one member must not end it for the others");
    }

    @Test
    void theStorySnapshotMarksSettingsAndListsSessions() {
        seedProgressAndPreferences();
        kit.runtime().sessions().open(SessionOwner.player(ALICE), "test:solo", "v1");

        PlayerStateAdmin.StoryState story = admin().story(ALICE);

        assertTrue(story.narrative());
        assertTrue(story.variables().stream().anyMatch(line -> line.id().equals("mysticquests:ui.tracker") && line.preference()));
        assertTrue(story.variables().stream().anyMatch(line -> line.id().equals("hyzion:keys_found") && !line.preference()));
        assertTrue(story.variables().stream().anyMatch(line -> line.owner().startsWith("quest/")),
                "Per-quest story state is listed with its owner");
        assertEquals(1, story.activeSessions());
    }

    @Test
    void partsParseTheWaysStaffTypeThem() {
        assertEquals(Part.STORY_STATE, Part.parse("story"));
        assertEquals(Part.VARIABLES, Part.parse("vars"));
        assertEquals(Part.PREFERENCES, Part.parse("settings"));
        assertNull(Part.parse("everything"));
        assertEquals(Set.of(Part.QUESTS, Part.TAGS, Part.VARIABLES, Part.STORY_STATE, Part.SESSIONS), Part.progress());
        assertNotNull(Part.QUESTS.label());
    }
}
