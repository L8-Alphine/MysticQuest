package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.trial.TrialNetworkStatus;
import org.hyzionstudios.mysticquests.api.trial.TrialReceipt;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig.NetworkTrialsConfig;
import org.hyzionstudios.mysticquests.content.BundledPackages;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.service.trial.network.SqlTrialStore;
import org.hyzionstudios.mysticquests.service.trial.network.TrialDialect;
import org.hyzionstudios.mysticquests.service.trial.network.TrialStoreSchema;
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
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Statement;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * R-1 (MysticRPG PO addendum 2026-10-24): the shared-SQL trial store against H2, with two servers of one
 * realm on one database: claim, receipts, handoff and continuation with the same completion id, fencing
 * (one server runs a run at a time), one run per player and trial, transfer holds, expiry, content
 * version checks, pending results after a store outage, realm isolation and the migration's dry run and
 * rollback. Store calls run on a direct executor, so every step is deterministic.
 */
final class NetworkTrialsTest {
    private static final ObjectMapper MAPPER = Json.createMapper();
    private static final UUID ALICE = UUID.fromString("00000000-0000-0000-0000-00000000a11c");
    private static final String WARRIOR = "mysticrpg:trial/warrior";
    private static final String WARRIOR_QUEST = "mysticrpg_trials:warrior";
    private static final String REALM = "realm:avalon";

    @TempDir
    Path root;

    private String url;
    private Connection keepAlive;
    private final Clock clock = new MutableClock();
    private final List<Server> servers = new ArrayList<>();

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

    private MutableClock time() {
        return (MutableClock) clock;
    }

    /** One server: its own quest data, its own trial service, the shared database. */
    private final class Server {
        final String id;
        final LoadedContent content;
        final MysticQuestsEventBus bus = new MysticQuestsEventBus(null);
        final PlayerQuestService quests;
        final SqlTrialStore store;
        final QuestTrialService trials;
        final Set<UUID> online = ConcurrentHashMap.newKeySet();
        final List<String> warnings = new ArrayList<>();

        Server(String id, String realm, String packageVersionOverride) throws Exception {
            this.id = id;
            Path home = root.resolve(id);
            Path packages = home.resolve("packages");
            BundledPackages.install(packages, home.resolve("data/bundled-packages.json"), MAPPER);
            if (packageVersionOverride != null) {
                Path manifest = packages.resolve("mysticrpg_trials/package.yml");
                String text = java.nio.file.Files.readString(manifest).replace("version: 1.0.0", "version: " + packageVersionOverride);
                java.nio.file.Files.writeString(manifest, text);
            }
            content = new QuestContentLoader(MAPPER).load(packages);
            MysticStateStore state = new MysticStateStore(bus);
            PlayerSessionService sessions = new PlayerSessionService(null);
            QuestNotificationService notifications = new QuestNotificationService(sessions, null);
            quests = new PlayerQuestService(() -> content, new JsonQuestStorage(home.resolve("data/players"), MAPPER),
                    new ScopedStateService(state, null, false, false), null, null, null, notifications, sessions, null,
                    null);
            quests.bindLifecycleEvents(bus);
            store = new SqlTrialStore(settings(realm, id));
            store.open();
            TrialNetworking networking = new TrialNetworking(store, Runnable::run, (player, work) -> work.run(),
                    online::contains, realm, id, 30_000L, 120_000L, 45_000L, true, List.of());
            trials = new QuestTrialService(TrialQuests.of(quests, () -> content, notifications),
                    home.resolve(QuestTrialService.LEDGER_FILE), MAPPER, bus, clock, warnings::add, networking);
            trials.start();
        }

        void join(UUID player) {
            online.add(player);
            trials.onJoin(player);
        }

        void leave(UUID player) {
            online.remove(player);
            trials.onDisconnect(player);
        }

        void close() {
            trials.close();
            store.close();
        }
    }

    private NetworkTrialsConfig settings(String realm, String serverId) {
        return new NetworkTrialsConfig(true, url, "sa", "", "", realm, serverId, "mq_", 30, 120, 45, "apply")
                .withDefaults();
    }

    private Server server(String id) throws Exception {
        Server server = new Server(id, REALM, null);
        servers.add(server);
        return server;
    }

    @BeforeEach
    void setUp() throws SQLException {
        url = "jdbc:h2:mem:trials_" + UUID.randomUUID().toString().replace("-", "") + ";DB_CLOSE_DELAY=-1";
        keepAlive = DriverManager.getConnection(url, "sa", "");
    }

    @AfterEach
    void tearDown() throws SQLException {
        servers.forEach(Server::close);
        keepAlive.close();
    }

    private static void finishWarrior(Server server) {
        server.quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "warriors", 3);
        server.quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "brawlers", 2);
    }

    private int rows(String sql) throws SQLException {
        try (Statement statement = keepAlive.createStatement(); ResultSet result = statement.executeQuery(sql)) {
            result.next();
            return result.getInt(1);
        }
    }

    @Test
    @DisplayName("a run is claimed, resolved once, and its receipt is network-backed and verifiable")
    void claimAndReceipt() throws Exception {
        Server a = server("a");
        a.join(ALICE);
        a.trials.heartbeat();
        assertTrue(a.trials.networkBacked());
        assertTrue(a.trials.networkStatus().capabilities().contains(TrialNetworkStatus.SHARED_SQL_CAPABILITY));

        CompletableFuture<TrialResult> run = a.trials.start(ALICE, WARRIOR);
        assertEquals(1, rows("SELECT COUNT(*) FROM mq_trial_slots"));
        finishWarrior(a);
        TrialResult result = run.join();
        assertEquals(TrialStatus.COMPLETED, result.status());

        TrialReceipt receipt = a.trials.receipt(result.completionId()).orElseThrow();
        assertTrue(receipt.networkBacked());
        assertTrue(receipt.verifies());
        assertEquals(REALM, receipt.realmId());
        assertEquals("1.0.0", receipt.questVersion());
        assertEquals(ALICE, receipt.playerId());
        assertEquals(WARRIOR, receipt.trialId());
        assertEquals(2L, receipt.causalRevision(), "claimed at 1, resolved at 2");
        assertEquals("a", receipt.resolvedBy());
        assertEquals(TrialReceipt.evidenceId(REALM, result.completionId()), receipt.evidenceId());
        assertEquals(1, rows("SELECT COUNT(*) FROM mq_trial_results"));
        assertEquals(0, rows("SELECT COUNT(*) FROM mq_trial_slots"), "the slot is free again");
        assertTrue(a.warnings.isEmpty(), a.warnings.toString());

        // A later abandoned attempt does not hide the completed run that is evidence.
        a.trials.start(ALICE, WARRIOR);
        time().now = Instant.now().plusSeconds(5);
        a.trials.cancel(ALICE, WARRIOR, "changed my mind");
        assertEquals(TrialStatus.ABANDONED, a.trials.lastResult(ALICE, WARRIOR).orElseThrow().status());
        assertEquals(result.completionId(), a.trials.lastCompleted(ALICE, WARRIOR).orElseThrow().completionId());
    }

    @Test
    @DisplayName("a run follows its player: handed off on A, continued on B with the same completion id")
    void handoffContinues() throws Exception {
        Server a = server("a");
        Server b = server("b");
        a.join(ALICE);
        CompletableFuture<TrialResult> onA = a.trials.start(ALICE, WARRIOR);
        a.quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "warriors", 2);
        a.trials.recordDeath(ALICE);
        String completionId = a.trials.lastResult(ALICE, WARRIOR).map(TrialResult::completionId).orElse(null);
        assertEquals(null, completionId, "nothing resolved yet");

        a.leave(ALICE);
        TrialResult handedOff = onA.join();
        assertEquals(TrialStatus.ABANDONED, handedOff.status());
        assertEquals(QuestTrialService.HANDOFF_REASON, handedOff.reason());
        assertTrue(a.quests.activeSince(ALICE, WARRIOR_QUEST).isEmpty(), "the quest left A without an abandonment");
        assertEquals(PlayerQuestService.QuestStatus.NOT_STARTED, a.quests.questStatus(ALICE, WARRIOR_QUEST));
        assertTrue(a.trials.lastResult(ALICE, WARRIOR).isEmpty(), "a handoff is not a result");

        b.join(ALICE);
        assertTrue(b.trials.isActive(ALICE, WARRIOR));
        assertEquals(2, b.quests.data(ALICE).activeQuests().get(WARRIOR_QUEST).objectiveProgress().get("warriors"),
                "progress continues");
        List<TrialResult> resolvedOnB = new ArrayList<>();
        b.bus.subscribe(org.hyzionstudios.mysticquests.api.trial.TrialResolved.class, event -> resolvedOnB.add(event.result()));
        b.quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "warriors", 3);
        b.quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "brawlers", 2);

        assertEquals(1, resolvedOnB.size());
        TrialResult result = resolvedOnB.getFirst();
        assertEquals(TrialStatus.COMPLETED, result.status());
        assertEquals(handedOff.completionId(), result.completionId(), "one run, one completion id across servers");
        assertEquals(1, result.deaths(), "the death on A counts on B: no reset by switching servers");
        TrialReceipt receipt = b.trials.receipt(result.completionId()).orElseThrow();
        assertEquals("b", receipt.resolvedBy());
        assertTrue(receipt.causalRevision() > 2, "claim, death checkpoint, handoff, take-over, resolution");

        // Evidence continuity: A sees the result B wrote once the player is back.
        b.leave(ALICE);
        a.join(ALICE);
        assertEquals(receipt, a.trials.receipt(result.completionId()).orElseThrow());
        assertEquals(result.completionId(), a.trials.lastResult(ALICE, WARRIOR).orElseThrow().completionId());
    }

    @Test
    @DisplayName("fencing: a server whose lease ran out loses the run; the new owner's writes win")
    void fencing() throws Exception {
        Server a = server("a");
        Server b = server("b");
        a.join(ALICE);
        CompletableFuture<TrialResult> onA = a.trials.start(ALICE, WARRIOR);

        // A stops renewing (cut off from the store); its lease expires and B takes the run over.
        time().now = time().now.plus(Duration.ofSeconds(31));
        b.join(ALICE);
        assertTrue(b.trials.isActive(ALICE, WARRIOR));

        // A's next checkpoint is fenced: it drops its copy without a result.
        a.trials.heartbeat();
        assertFalse(a.trials.isActive(ALICE, WARRIOR));
        assertEquals(TrialStatus.ABANDONED, onA.join().status());
        assertTrue(onA.join().reason().startsWith(QuestTrialService.HANDOFF_REASON));
        assertTrue(a.quests.activeSince(ALICE, WARRIOR_QUEST).isEmpty());

        finishWarrior(b);
        assertEquals(TrialStatus.COMPLETED, b.trials.lastResult(ALICE, WARRIOR).orElseThrow().status());
        assertEquals(1, rows("SELECT COUNT(*) FROM mq_trial_results"), "exactly one result");
    }

    @Test
    @DisplayName("one run per realm, player and trial: a second server cannot start a duplicate")
    void oneRunPerSlot() throws Exception {
        Server a = server("a");
        Server b = server("b");
        a.join(ALICE);
        a.trials.start(ALICE, WARRIOR);
        b.online.add(ALICE);
        TrialResult duplicate = b.trials.start(ALICE, WARRIOR).join();
        assertEquals(TrialStatus.ABANDONED, duplicate.status());
        assertTrue(duplicate.reason().contains("already has the trial"), duplicate.reason());
        assertTrue(a.trials.isActive(ALICE, WARRIOR), "the original run is untouched");
    }

    @Test
    @DisplayName("transfer guard: allowed only to a live member of the same realm's shared store")
    void transferHolds() throws Exception {
        Server a = server("a");
        a.join(ALICE);
        assertTrue(a.trials.transferHold(ALICE, "b").isEmpty(), "no trial, no hold");
        a.trials.start(ALICE, WARRIOR);
        a.trials.heartbeat();
        assertTrue(a.trials.transferHold(ALICE, "b").orElseThrow().contains("does not use"), "b has no heartbeat yet");
        server("b");
        a.trials.heartbeat();
        assertTrue(a.trials.transferHold(ALICE, "b").isEmpty(), "both servers use the shared store");
        assertTrue(a.trials.transferHold(ALICE, "c").isPresent(), "an unknown server");
        assertTrue(a.trials.transferHold(ALICE, "").isPresent());
        assertTrue(a.trials.transferHold(ALICE).isPresent(), "a contract-1 caller names no target: held");

        // A member whose heartbeat is stale is no target.
        time().now = time().now.plus(Duration.ofSeconds(46));
        a.trials.heartbeat();
        assertTrue(a.trials.transferHold(ALICE, "b").isPresent());

        // The store failing: held, and not network-backed.
        keepAlive.createStatement().execute("DROP TABLE mq_trial_servers");
        a.trials.heartbeat();
        assertFalse(a.trials.networkBacked());
        assertTrue(a.trials.transferHold(ALICE, "b").orElseThrow().contains("unavailable"));
    }

    @Test
    @DisplayName("a handed-off run nobody continues in time is abandoned by the sweep")
    void expiredHandoffIsAbandoned() throws Exception {
        Server a = server("a");
        Server b = server("b");
        a.join(ALICE);
        CompletableFuture<TrialResult> run = a.trials.start(ALICE, WARRIOR);
        a.leave(ALICE);
        String completionId = run.join().completionId();
        time().now = time().now.plus(Duration.ofSeconds(121));
        b.trials.heartbeat();
        assertEquals(1, rows("SELECT COUNT(*) FROM mq_trial_results WHERE result_status = 'ABANDONED'"));
        assertEquals(0, rows("SELECT COUNT(*) FROM mq_trial_slots"));
        b.join(ALICE);
        assertFalse(b.trials.isActive(ALICE, WARRIOR), "too late to continue");
        assertEquals(TrialStatus.ABANDONED, b.trials.receipt(completionId).orElseThrow().status());
    }

    @Test
    @DisplayName("a run is never continued on different trial content: the version mismatch abandons it")
    void versionMismatchAbandons() throws Exception {
        Server a = server("a");
        Server b = new Server("b", REALM, "1.0.1");
        servers.add(b);
        a.join(ALICE);
        CompletableFuture<TrialResult> run = a.trials.start(ALICE, WARRIOR);
        a.leave(ALICE);
        b.join(ALICE);
        assertFalse(b.trials.isActive(ALICE, WARRIOR));
        TrialReceipt receipt = b.trials.receipt(run.join().completionId()).orElseThrow();
        assertEquals(TrialStatus.ABANDONED, receipt.status());
        assertEquals("1.0.0", receipt.questVersion());
    }

    @Test
    @DisplayName("a result the store could not take is kept pending and written with its receipt later")
    void pendingResultRetried() throws Exception {
        Server a = server("a");
        a.join(ALICE);
        CompletableFuture<TrialResult> run = a.trials.start(ALICE, WARRIOR);
        keepAlive.createStatement().execute("ALTER TABLE mq_trial_results RENAME TO mq_trial_results_away");
        finishWarrior(a);
        TrialResult result = run.join();
        assertEquals(TrialStatus.COMPLETED, result.status(), "the caller is told, it never waits on the store");
        assertTrue(a.trials.receipt(result.completionId()).isEmpty(), "no receipt before the store has it");
        assertEquals(1, a.trials.networkStatus().pendingResults());

        keepAlive.createStatement().execute("ALTER TABLE mq_trial_results_away RENAME TO mq_trial_results");
        a.trials.heartbeat();
        TrialReceipt receipt = a.trials.receipt(result.completionId()).orElseThrow();
        assertTrue(receipt.networkBacked() && receipt.verifies());
        assertEquals(0, a.trials.networkStatus().pendingResults());
    }

    @Test
    @DisplayName("realms are isolated: another realm's server never sees or continues a run")
    void realmIsolation() throws Exception {
        Server a = server("a");
        Server other = new Server("x", "realm:other", null);
        servers.add(other);
        a.join(ALICE);
        a.trials.start(ALICE, WARRIOR);
        a.leave(ALICE);
        other.join(ALICE);
        assertFalse(other.trials.isActive(ALICE, WARRIOR));
        other.trials.heartbeat();
        a.trials.heartbeat();
        assertFalse(a.trials.networkStatus().peers().contains("x"));
    }

    @Test
    @DisplayName("a crash: the server's own runs are handed off at the next start and continue elsewhere")
    void crashHandsOff() throws Exception {
        Server a = server("a");
        Server b = server("b");
        a.join(ALICE);
        a.trials.start(ALICE, WARRIOR);
        // A crashes: a new service over A's data starts without close().
        QuestTrialService restarted = new QuestTrialService(TrialQuests.of(a.quests, () -> a.content, null),
                root.resolve("a").resolve(QuestTrialService.LEDGER_FILE), MAPPER, a.bus, clock, a.warnings::add,
                new TrialNetworking(a.store, Runnable::run, (player, work) -> work.run(), player -> false, REALM, "a",
                        30_000L, 120_000L, 45_000L, true, List.of()));
        restarted.start();
        assertTrue(a.quests.activeSince(ALICE, WARRIOR_QUEST).isEmpty(), "the stale quest left A");
        b.join(ALICE);
        assertTrue(b.trials.isActive(ALICE, WARRIOR));
        restarted.close();
    }

    @Test
    @DisplayName("schema: apply is idempotent and versioned; dry run and rollback list statements only")
    void schema() throws Exception {
        TrialStoreSchema schema = new TrialStoreSchema(TrialDialect.H2, "mq_");
        List<String> dryRun = schema.dryRun(0);
        assertTrue(dryRun.stream().anyMatch(line -> line.startsWith("CREATE TABLE IF NOT EXISTS mq_trial_runs")));
        assertEquals(0, schema.currentVersion(keepAlive), "a dry run applied nothing");
        assertEquals(1, schema.apply(keepAlive));
        assertEquals(1, schema.apply(keepAlive), "applying again changes nothing");
        assertTrue(schema.dryRun(1).isEmpty());
        List<String> rollback = schema.rollback(1);
        assertEquals("DROP TABLE IF EXISTS mq_trial_results", rollback.get(1));
        assertEquals(1, schema.currentVersion(keepAlive), "listing the rollback ran nothing");

        SqlTrialStore dry = new SqlTrialStore(new NetworkTrialsConfig(true, url, "sa", "", "", REALM, "d", "dry_", 30,
                120, 45, "dry-run").withDefaults());
        assertFalse(dry.open().isEmpty(), "the statements a dry run would apply");
        assertEquals(0, dry.schemaVersion());
        dry.close();
        assertEquals(TrialDialect.MYSQL, TrialDialect.of("jdbc:mysql://db/x").orElseThrow());
        assertEquals("jdbc:mysql://db/x?permitMysqlScheme", TrialDialect.MYSQL.connectUrl("jdbc:mysql://db/x"));
        assertEquals(TrialDialect.POSTGRESQL, TrialDialect.of("jdbc:postgresql://db/x").orElseThrow());
        assertEquals(TrialDialect.MARIADB, TrialDialect.of("jdbc:mariadb://db/x").orElseThrow());
    }

    @Test
    @DisplayName("configuration: off by default, every bound validated, nothing clamped")
    void configuration() {
        assertFalse(NetworkTrialsConfig.DEFAULTS.on());
        assertTrue(NetworkTrialsConfig.DEFAULTS.problems().isEmpty());
        NetworkTrialsConfig bad = new NetworkTrialsConfig(true, "jdbc:oracle:x", "", "", "", "Avalon", "", "1x", 5, 5000,
                1, "maybe").withDefaults();
        assertEquals(8, bad.problems().size(), bad.problems().toString());
        assertTrue(settings(REALM, "a").problems().isEmpty());
    }

    @Test
    @DisplayName("receipts detect tampering")
    void receiptDigest() {
        TrialReceipt receipt = TrialReceipt.of(WARRIOR, ALICE, REALM, WARRIOR_QUEST, "1.0.0", "c1", TrialStatus.COMPLETED,
                3L, Instant.ofEpochMilli(1_000L), "a", true);
        assertTrue(receipt.verifies());
        TrialReceipt forged = new TrialReceipt(receipt.trialId(), receipt.playerId(), receipt.realmId(), receipt.questId(),
                receipt.questVersion(), receipt.evidenceId(), receipt.completionId(), TrialStatus.COMPLETED, 4L,
                receipt.resolvedAt(), receipt.resolvedBy(), true, receipt.receipt());
        assertFalse(forged.verifies());
        assertNotEquals(TrialReceipt.evidenceId("realm:a", "c1"), TrialReceipt.evidenceId("realm:b", "c1"));
    }

    @Test
    @DisplayName("standalone receipts carry the contract fields but are not network-backed")
    void localReceipts() throws IOException {
        Path home = root.resolve("local");
        BundledPackages.install(home.resolve("packages"), home.resolve("data/bundled-packages.json"), MAPPER);
        LoadedContent content = new QuestContentLoader(MAPPER).load(home.resolve("packages"));
        MysticQuestsEventBus bus = new MysticQuestsEventBus(null);
        PlayerSessionService sessions = new PlayerSessionService(null);
        QuestNotificationService notifications = new QuestNotificationService(sessions, null);
        PlayerQuestService quests = new PlayerQuestService(() -> content, new JsonQuestStorage(home.resolve("data/players"),
                MAPPER), new ScopedStateService(new MysticStateStore(bus), null, false, false), null, null, null,
                notifications, sessions, null, null);
        quests.bindLifecycleEvents(bus);
        QuestTrialService local = new QuestTrialService(TrialQuests.of(quests, () -> content, notifications),
                null, MAPPER, bus, clock, message -> { });
        local.start();
        CompletableFuture<TrialResult> run = local.start(ALICE, WARRIOR);
        local.recordDeath(ALICE);
        quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "warriors", 3);
        quests.setObjectiveProgress(ALICE, WARRIOR_QUEST, "brawlers", 2);
        TrialReceipt receipt = local.receipt(run.join().completionId()).orElseThrow();
        assertFalse(receipt.networkBacked());
        assertTrue(receipt.verifies());
        assertEquals(TrialReceipt.LOCAL_REALM, receipt.realmId());
        assertEquals(3L, receipt.causalRevision(), "start 1, death 2, resolution 3");
        assertFalse(local.networkBacked());
        assertTrue(local.networkStatus().capabilities().isEmpty());
        local.close();
    }
}
