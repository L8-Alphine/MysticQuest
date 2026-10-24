package org.hyzionstudios.mysticquests.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.objective.TrackedObjectivesChanged;
import org.hyzionstudios.mysticquests.api.objective.TrackedQuest;
import org.hyzionstudios.mysticquests.api.objective.TrackingResult;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.storage.JsonQuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-62 (MysticRPG PO addendum 2026-10-24): the objective DTO, tracking identity, track and untrack,
 * completion, reload, change events, the tracker claim and hidden-quest protection.
 */
final class ObjectiveTrackingServiceTest {
    private static final ObjectMapper MAPPER = Json.createMapper();
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");

    @TempDir
    Path root;

    private LoadedContent content;
    private PlayerQuestService quests;
    private MysticQuestsEventBus bus;
    private ObjectiveTrackingService objectives;
    private final List<TrackedObjectivesChanged> events = new ArrayList<>();

    @BeforeEach
    void setUp() throws IOException {
        Path packages = root.resolve("packages/demo");
        Files.createDirectories(packages);
        Files.writeString(packages.resolve("package.yml"), "enabled: true\nversion: 1.0.0\n");
        Files.writeString(packages.resolve("quests.yml"), """
                quests:
                  wolves:
                    displayName: "Wolf Trouble"
                    category: side
                    objectives:
                      - { id: pelts, displayName: "Collect pelts", type: kill, target: Wolf_Black, amount: 3 }
                      - { id: bonus, displayName: "Optional: the alpha", type: kill, target: Wolf_Black, amount: 1, optional: true }
                  courier:
                    displayName: "Courier"
                    objectives:
                      - { id: deliver, displayName: "Deliver the letter", type: kill, target: Spider, amount: 1 }
                  awakening:
                    displayName: "Whispers"
                    hidden: true
                    objectives:
                      - { id: listen, displayName: "Listen", type: kill, target: Spider, amount: 1 }
                """);
        content = new QuestContentLoader(MAPPER).load(root.resolve("packages"));
        bus = new MysticQuestsEventBus(null);
        PlayerSessionService sessions = new PlayerSessionService(null);
        quests = new PlayerQuestService(() -> content, new JsonQuestStorage(root.resolve("data/players"), MAPPER),
                new ScopedStateService(new MysticStateStore(bus), null, false, false), null, null, null,
                new QuestNotificationService(sessions, null), sessions, null, null);
        objectives = new ObjectiveTrackingService(quests, bus);
        objectives.subscribe(events::add);
    }

    @Test
    @DisplayName("the tracked quest as a DTO: objectives, progress, optional flags and a revision")
    void trackedDto() {
        assertTrue(objectives.tracked(ALICE).isEmpty(), "nothing active, nothing tracked");
        assertTrue(quests.startQuest(ALICE, "demo:wolves").success());
        assertEquals("1.0.0", quests.packageVersion("demo:wolves"), "the quest version of completion receipts");
        assertEquals("", quests.packageVersion("demo:nope"));
        TrackedQuest tracked = objectives.tracked(ALICE).orElseThrow();
        assertEquals("demo:wolves", tracked.questId());
        assertEquals("Wolf Trouble", tracked.displayName());
        assertEquals("side", tracked.category());
        assertTrue(tracked.tracked());
        assertFalse(tracked.trial());
        assertEquals(2, tracked.objectives().size());
        assertFalse(tracked.objectives().get(0).optional());
        assertTrue(tracked.objectives().get(1).optional());
        String before = tracked.revision();

        quests.setObjectiveProgress(ALICE, "demo:wolves", "pelts", 2);
        TrackedQuest after = objectives.tracked(ALICE).orElseThrow();
        assertEquals(2, after.objectives().getFirst().current());
        assertEquals(3, after.objectives().getFirst().target());
        assertNotEquals(before, after.revision(), "progress changes the revision");
        assertFalse(events.isEmpty(), "every change is announced");
        assertTrue(events.stream().allMatch(event -> event.player().equals(ALICE)));
    }

    @Test
    @DisplayName("track and untrack: idempotent, only the player's own active quests")
    void trackAndUntrack() {
        quests.startQuest(ALICE, "demo:wolves");
        quests.startQuest(ALICE, "demo:courier");
        assertEquals("demo:wolves", objectives.tracked(ALICE).orElseThrow().questId(), "the first started is tracked");

        TrackingResult tracked = objectives.track(ALICE, "demo:courier");
        assertTrue(tracked.success() && tracked.changed());
        assertEquals("demo:courier", objectives.tracked(ALICE).orElseThrow().questId());
        TrackingResult again = objectives.track(ALICE, "demo:courier");
        assertTrue(again.success());
        assertFalse(again.changed(), "a double click changes nothing");

        assertFalse(objectives.track(ALICE, "demo:nope").success());
        assertFalse(objectives.track(UUID.randomUUID(), "demo:courier").success(), "not that player's quest");

        assertFalse(objectives.untrack(ALICE, "demo:wolves").changed(), "not tracked: nothing to do");
        assertTrue(objectives.untrack(ALICE, "demo:courier").changed());
        assertEquals("demo:wolves", objectives.tracked(ALICE).orElseThrow().questId(), "falls back to the other quest");
        assertEquals(2, objectives.active(ALICE).size());
    }

    @Test
    @DisplayName("completion moves tracking on; nothing is left tracked when nothing is active")
    void completion() {
        quests.startQuest(ALICE, "demo:courier");
        quests.setObjectiveProgress(ALICE, "demo:courier", "deliver", 1);
        assertTrue(objectives.tracked(ALICE).isEmpty());
        assertEquals(PlayerQuestService.QuestStatus.COMPLETED, quests.questStatus(ALICE, "demo:courier"));
        assertFalse(objectives.track(ALICE, "demo:courier").success(), "a completed quest cannot be tracked");
    }

    @Test
    @DisplayName("hidden quests never leave MysticQuests: not tracked, not listed, refused like unknown ones")
    void hiddenProtection() {
        quests.startQuest(ALICE, "demo:awakening");
        assertTrue(objectives.tracked(ALICE).isEmpty(), "the hidden quest is tracked inside MysticQuests only");
        assertTrue(objectives.active(ALICE).isEmpty());
        TrackingResult hidden = objectives.track(ALICE, "demo:awakening");
        TrackingResult unknown = objectives.track(ALICE, "demo:does_not_exist");
        assertFalse(hidden.success());
        assertEquals(unknown.message(), hidden.message(), "no way to tell a hidden quest from a missing one");
        assertFalse(objectives.untrack(ALICE, "demo:awakening").success());

        quests.startQuest(ALICE, "demo:wolves");
        assertEquals(List.of("demo:wolves"), objectives.active(ALICE).stream().map(TrackedQuest::questId).toList());
    }

    @Test
    @DisplayName("reload announces every listed player; the tracker claim is counted and released")
    void reloadAndClaim() throws Exception {
        events.clear();
        objectives.reloaded(List.of(ALICE));
        assertEquals("reload", events.getFirst().reason());

        int[] changes = {0};
        objectives.onClaimsChanged(() -> changes[0]++);
        assertFalse(objectives.trackerClaimed());
        AutoCloseable claim = objectives.claimTracker("mysticrpg");
        assertTrue(objectives.trackerClaimed());
        claim.close();
        claim.close();
        assertFalse(objectives.trackerClaimed());
        assertEquals(2, changes[0], "claimed once, released once");
    }
}
