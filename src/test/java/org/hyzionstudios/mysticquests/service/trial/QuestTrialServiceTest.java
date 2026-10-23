package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.MysticQuestsApi;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.content.BundledPackages;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.QuestLifecycleEvents;
import org.hyzionstudios.mysticquests.model.TrialDefinition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.storage.JsonQuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The trial check MysticRPG consumes: lifecycle events with completion ids (Q-1), trial runs that
 * resolve exactly once, abandon on logout, death and time limits, restart recovery and the shipped
 * {@code mysticrpg_trials} package, against the real quest engine and the bundled content.
 */
final class QuestTrialServiceTest {
    private static final ObjectMapper MAPPER = Json.createMapper();
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final String WARRIOR = "mysticrpg:trial/warrior";
    private static final String WARRIOR_QUEST = "mysticrpg_trials:warrior";

    @TempDir
    Path root;

    private LoadedContent content;
    private MysticQuestsEventBus bus;
    private MysticStateStore state;
    private PlayerQuestService quests;
    private QuestNotificationService notifications;
    private MutableClock clock;
    private QuestTrialService trials;
    private final List<String> warnings = new ArrayList<>();

    /** A clock tests move forward; starts at the real time, because quest runs are stamped with it. */
    private static final class MutableClock extends Clock {
        Instant now = Instant.now();

        @Override
        public ZoneOffset getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Path packages = root.resolve("packages");
        BundledPackages.install(packages, root.resolve("data/bundled-packages.json"), MAPPER);
        content = new QuestContentLoader(MAPPER).load(packages);
        bus = new MysticQuestsEventBus(null);
        state = new MysticStateStore(bus);
        PlayerSessionService sessions = new PlayerSessionService(null);
        notifications = new QuestNotificationService(sessions, null);
        quests = new PlayerQuestService(() -> content, new JsonQuestStorage(root.resolve("data/players"), MAPPER),
                new ScopedStateService(state, null, false, false), null, null, null, notifications, sessions, null,
                null);
        quests.bindLifecycleEvents(bus);
        clock = new MutableClock();
        trials = newService();
        trials.start();
    }

    private QuestTrialService newService() {
        return new QuestTrialService(TrialQuests.of(quests, () -> content, notifications),
                root.resolve(QuestTrialService.LEDGER_FILE), MAPPER, bus, clock, warnings::add);
    }

    @AfterEach
    void tearDown() {
        trials.close();
    }

    /** Completes every required objective of the warrior trial. */
    private void finishWarriorObjectives() {
        quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "warriors", 3);
        quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "brawlers", 2);
    }

    @Test
    @DisplayName("the bundled package declares all six Q16 class trials under MysticRPG's ids")
    void sixClassTrialsShip() {
        for (String type : List.of("warrior", "mage", "ranger", "rogue", "paladin", "vanguard")) {
            assertEquals("mysticrpg_trials:" + type, trials.questFor("mysticrpg:trial/" + type).orElseThrow());
            assertNotNull(content.quests().get("mysticrpg_trials:" + type).trial().ranking(), type + " is ranked (Q11)");
        }
    }

    @Test
    @DisplayName("lifecycle events: one start and one completion per run, with one completion id")
    void lifecycleEventsAreIdempotent() {
        List<Object> events = new ArrayList<>();
        bus.subscribe(QuestLifecycleEvents.QuestStarted.class, events::add);
        bus.subscribe(QuestLifecycleEvents.QuestCompleted.class, events::add);

        assertTrue(quests.startQuestAsTrial(ALICE, WARRIOR_QUEST).success());
        finishWarriorObjectives();
        assertFalse(quests.completeQuest(ALICE, WARRIOR_QUEST).success(), "a finished quest cannot complete twice");
        assertFalse(quests.failQuest(ALICE, WARRIOR_QUEST, "late").success());

        assertEquals(2, events.size());
        QuestLifecycleEvents.QuestStarted started = (QuestLifecycleEvents.QuestStarted) events.get(0);
        QuestLifecycleEvents.QuestCompleted completed = (QuestLifecycleEvents.QuestCompleted) events.get(1);
        assertEquals(started.completionId(), completed.completionId());
        assertEquals(QuestLifecycleEvents.completionId(ALICE, WARRIOR_QUEST, started.startedAt()),
                completed.completionId(), "derived from the persisted start time, so stable across restarts");
        assertEquals(3, completed.objectiveProgress().get("warriors"));
    }

    @Test
    @DisplayName("players cannot take a trial quest from the board or start it by hand")
    void trialQuestsAreNotOrdinaryQuests() {
        assertFalse(quests.startQuest(ALICE, WARRIOR_QUEST).success());
        assertTrue(quests.availableJournal(ALICE).stream().noneMatch(entry -> entry.questId().startsWith("mysticrpg_trials:")));
    }

    @Test
    @DisplayName("a completed trial resolves once, ranked, with its evidence kept by completion id")
    void completionResolvesOnce() {
        List<TrialResult> posted = new ArrayList<>();
        bus.subscribe(org.hyzionstudios.mysticquests.api.trial.TrialResolved.class, resolved -> posted.add(resolved.result()));
        CompletableFuture<TrialResult> run = trials.start(ALICE, WARRIOR);
        assertSame(run, trials.start(ALICE, WARRIOR), "a double click starts one run");
        assertTrue(trials.isActive(ALICE, WARRIOR));
        assertFalse(run.isDone());

        finishWarriorObjectives();

        TrialResult result = run.join();
        assertEquals(TrialStatus.COMPLETED, result.status());
        assertTrue(result.ranked());
        assertEquals("1.0.0", result.packageVersion(), "the trial package's version, for evidence records");
        assertTrue(result.score() > 0.7d && result.score() <= 0.8d,
                "no time over par, no deaths, optional objective missed: 1 - 0.2 = 0.8, got " + result.score());
        assertEquals(1, posted.size());
        assertEquals(result.completionId(), trials.evidence(result.completionId()).orElseThrow().completionId());
        assertEquals(result.completionId(), trials.lastResult(ALICE, WARRIOR).orElseThrow().completionId());
        assertFalse(trials.isActive(ALICE, WARRIOR));

        // A retry is a new run with a new completion id.
        CompletableFuture<TrialResult> retry = trials.start(ALICE, WARRIOR);
        assertFalse(retry.isDone());
        assertTrue(trials.cancel(ALICE, WARRIOR, "changed my mind"));
        TrialResult cancelled = retry.join();
        assertEquals(TrialStatus.ABANDONED, cancelled.status());
        assertFalse(cancelled.completionId().equals(result.completionId()));
        assertEquals(2, posted.size(), "the cancel posted exactly one more result");
    }

    @Test
    @DisplayName("logging out abandons the trial: the future completes, it never hangs")
    void logoutAbandons() {
        CompletableFuture<TrialResult> run = trials.start(ALICE, WARRIOR);
        assertTrue(trials.transferHold(ALICE).isPresent(), "an active trial holds the player on this server");

        trials.onDisconnect(ALICE);

        assertTrue(run.isDone());
        assertEquals(TrialStatus.ABANDONED, run.join().status());
        assertEquals(PlayerQuestService.QuestStatus.ABANDONED, quests.questStatus(ALICE, WARRIOR_QUEST));
        assertTrue(trials.transferHold(ALICE).isEmpty());
        assertFalse(trials.start(ALICE, WARRIOR).isDone(), "an abandoned trial can be run again");
    }

    @Test
    @DisplayName("a death past maxDeaths fails the trial")
    void deathLimitFails() {
        CompletableFuture<TrialResult> run = trials.start(ALICE, "mysticrpg:trial/rogue");
        trials.recordDeath(ALICE);
        assertFalse(run.isDone(), "the rogue trial allows one death");
        trials.recordDeath(ALICE);
        TrialResult result = run.join();
        assertEquals(TrialStatus.FAILED, result.status());
        assertEquals(2, result.deaths());
    }

    @Test
    @DisplayName("the time limit fails an overdue trial")
    void timeLimitFails() {
        CompletableFuture<TrialResult> run = trials.start(ALICE, "mysticrpg:trial/rogue");
        assertTrue(trials.overdue(clock.now).isEmpty());
        clock.now = clock.now.plus(Duration.ofSeconds(901));
        List<String> overdue = trials.overdue(clock.now);
        assertEquals(1, overdue.size());
        trials.enforceTimeLimit(overdue.getFirst());
        assertEquals(TrialStatus.FAILED, run.join().status());
    }

    @Test
    @DisplayName("a run left active by a crash is abandoned on the next start, never left hanging")
    void crashRecoveryAbandons() {
        trials.start(ALICE, WARRIOR);
        String completionId = QuestLifecycleEvents.completionId(ALICE, WARRIOR_QUEST,
                quests.activeSince(ALICE, WARRIOR_QUEST).orElseThrow());

        // A second service over the same ledger stands in for the server after a crash.
        QuestTrialService restarted = newService();
        restarted.start();

        assertEquals(TrialStatus.ABANDONED, restarted.evidence(completionId).orElseThrow().status());
        assertTrue(quests.activeSince(ALICE, WARRIOR_QUEST).isEmpty());
        restarted.close();
    }

    @Test
    @DisplayName("unknown trials are refused at once; a stopping service abandons and refuses")
    void refusalsAndShutdown() {
        assertEquals(TrialStatus.REFUSED, trials.start(ALICE, "mysticrpg:trial/nope").join().status());
        CompletableFuture<TrialResult> run = trials.start(ALICE, WARRIOR);
        trials.close();
        assertEquals(TrialStatus.ABANDONED, run.join().status());
        assertEquals(TrialStatus.REFUSED, trials.start(ALICE, WARRIOR).join().status());
    }

    @Test
    @DisplayName("results survive a restart in data/trials.json")
    void resultsPersist() {
        CompletableFuture<TrialResult> run = trials.start(ALICE, WARRIOR);
        finishWarriorObjectives();
        TrialResult result = run.join();

        QuestTrialService restarted = newService();
        restarted.start();
        TrialResult reread = restarted.evidence(result.completionId()).orElseThrow();
        assertEquals(result.status(), reread.status());
        assertEquals(result.score(), reread.score(), 1e-9);
        assertEquals(result.metrics(), reread.metrics());
        restarted.close();
        assertTrue(warnings.isEmpty(), warnings.toString());
    }

    @Test
    @DisplayName("ranking: time over par, deaths and optional objectives move the score")
    void rankingFormula() {
        TrialDefinition.Ranking ranking = new TrialDefinition.Ranking(600, 0.3d, 0.1d, 0.2d);
        assertEquals(1.0d, ranking.score(300, 0, 1, 1), 1e-9);
        assertEquals(0.8d, ranking.score(300, 0, 1, 0), 1e-9);
        assertEquals(0.85d, ranking.score(900, 0, 1, 1), 1e-9);
        assertEquals(0.5d, ranking.score(1200, 2, 1, 1), 1e-9);
        assertEquals(0.0d, ranking.score(5000, 50, 1, 0), 1e-9);
    }

    @Test
    @DisplayName("quest tags for other mods: completed quests, player tags and package-scoped tags")
    void questTags() {
        MysticQuestsApi api = new MysticQuestsApi(state, null, null, null, null, quests, null, bus, null, trials);
        CompletableFuture<TrialResult> run = trials.start(ALICE, WARRIOR);
        assertFalse(api.hasQuestTag(ALICE, WARRIOR_QUEST));
        finishWarriorObjectives();
        run.join();
        assertTrue(api.hasQuestTag(ALICE, WARRIOR_QUEST));
        assertTrue(api.hasCompletedQuest(ALICE, WARRIOR_QUEST));

        api.addTag(ALICE, "mysticrpg_trials.oath_sworn");
        assertTrue(api.hasQuestTag(ALICE, "mysticrpg_trials:oath_sworn"));
        api.addTag(ALICE, "met_elder");
        assertTrue(api.hasQuestTag(ALICE, "met_elder"));
        assertFalse(api.hasQuestTag(ALICE, "mysticrpg_trials:mage"));
        assertSame(trials, api.trials());
    }
}
