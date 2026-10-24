package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.trial.QuestTrials;
import org.hyzionstudios.mysticquests.api.trial.TrialNetworkStatus;
import org.hyzionstudios.mysticquests.api.trial.TrialReceipt;
import org.hyzionstudios.mysticquests.api.trial.TrialResolved;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.QuestLifecycleEvents;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.TrialDefinition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService.QuestSnapshot;
import org.hyzionstudios.mysticquests.service.QuestResult;
import org.hyzionstudios.mysticquests.service.trial.network.SqlTrialStore;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.sql.SQLException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The trial check MysticRPG's class advancement, Paragon era challenges and recovery trial ask for
 * (ADR-012 in MysticRPG): starts a trial quest, watches it through the quest lifecycle events, and
 * resolves each run exactly once.
 *
 * <p><b>Exactly once.</b> A run resolves from the first of: its quest completing, failing or being
 * abandoned (lifecycle events, matched by completion id), the player leaving
 * ({@link #onDisconnect}), a death past the trial's limit ({@link #recordDeath}), its time limit
 * ({@link #enforceTimeLimit}), a cancel, or the server stopping ({@link #close}). Each path removes
 * the run before it completes the future, so the others find nothing; the result is saved under the
 * run's completion id before anyone is told.
 *
 * <p><b>Never hangs.</b> Every run is saved while active; a run found in the ledger at start-up (the
 * server crashed during it) is abandoned and its quest with it, so no run outlives a restart.
 *
 * <p><b>Network (MysticRPG PO addendum 2026-10-24, R-1).</b> With the shared-SQL trial store
 * ({@link TrialNetworking#networked()}), a run is claimed in the store when it starts (one run per
 * realm, player and trial), checkpointed with its deaths and objective progress on every death and
 * every heartbeat, and handed off instead of abandoned when its player leaves or the server stops:
 * the server the player joins next (within {@code handoffSeconds}) takes it over and continues it
 * with the same completion id. Every store write is a compare-and-set on the run's revision, so one
 * server runs a trial at a time; a server that loses a run drops its copy. Results are written with
 * their {@link TrialReceipt} in the same transaction that closes the run. The future of a run that was
 * handed off completes on the server it left with {@link TrialStatus#ABANDONED} and the reason
 * {@value #HANDOFF_REASON}; the run's real result is posted as {@link TrialResolved} on the server that
 * resolves it and is read back with {@link #lastResult} / {@link #receipt} on any server of the realm.
 *
 * <p>Every public method is synchronized or takes the lock briefly: trials are rare, and one lock
 * keeps the run table, the ledger and the quest state in step. Futures are completed after the lock is
 * released, and SQL runs only on the store executor, never under the lock.
 */
public final class QuestTrialService implements QuestTrials, AutoCloseable {

    /** Where results are saved, relative to the data folder. */
    public static final String LEDGER_FILE = "data/trials.json";

    /** The reason a handed-off run's future reports on the server the player left. */
    public static final String HANDOFF_REASON = "the trial continues on another server";

    /** Results of a player read from the shared store when they join. */
    private static final int RESULT_PREFETCH = 200;
    /** Expired runs abandoned per heartbeat. */
    private static final int SWEEP_LIMIT = 20;

    private final TrialQuests quests;
    private final TrialLedger ledger;
    private final ObjectMapper mapper;
    private final Clock clock;
    private final Consumer<String> warnings;
    @Nullable
    private final MysticQuestsEventBus bus;
    private final TrialNetworking networking;
    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    /** Runs in progress by completion id. */
    private final Map<String, Run> runs = new LinkedHashMap<>();

    /** Players whose runs another server still held when they joined; retried on every heartbeat. */
    private final Set<UUID> awaitingResume = ConcurrentHashMap.newKeySet();

    private boolean closed;

    /** Shared store health, written by the store thread. */
    private volatile long lastHeartbeatAt;
    private volatile Map<String, Long> members = Map.of();
    @Nullable
    private volatile String storeProblem;

    private static final class Run {
        final UUID player;
        final String trialId;
        final String questId;
        final String completionId;
        final Instant startedAt;
        final String questVersion;
        int deaths;
        /** Local: saved transitions. Network: the store row's revision (changed only on the store thread). */
        volatile long revision;
        /** Whether the shared store holds this run (set on the store thread). */
        volatile boolean claimed;
        final CompletableFuture<TrialResult> future = new CompletableFuture<>();

        Run(UUID player, String trialId, String questId, String completionId, Instant startedAt, int deaths,
            long revision, String questVersion) {
            this.player = player;
            this.trialId = trialId;
            this.questId = questId;
            this.completionId = completionId;
            this.startedAt = startedAt;
            this.deaths = deaths;
            this.revision = revision;
            this.questVersion = questVersion == null ? "" : questVersion;
        }

        TrialLedger.ActiveRun saved() {
            return new TrialLedger.ActiveRun(player, trialId, questId, completionId, startedAt, deaths, revision);
        }
    }

    /**
     * A trial service keeping everything on this server.
     *
     * @param ledgerFile {@link #LEDGER_FILE} under the data folder; null keeps results in memory
     * @param bus        where lifecycle events arrive and {@link TrialResolved} is posted; null in tests
     *                   that call the lifecycle handlers directly
     */
    public QuestTrialService(TrialQuests quests, @Nullable Path ledgerFile, ObjectMapper mapper,
                             @Nullable MysticQuestsEventBus bus, Clock clock, Consumer<String> warnings) {
        this(quests, ledgerFile, mapper, bus, clock, warnings, TrialNetworking.local("", "", false, List.of()));
    }

    /** A trial service on the shared trial store when {@code networking} has one (R-1). */
    public QuestTrialService(TrialQuests quests, @Nullable Path ledgerFile, ObjectMapper mapper,
                             @Nullable MysticQuestsEventBus bus, Clock clock, Consumer<String> warnings,
                             TrialNetworking networking) {
        this.quests = quests;
        this.ledger = new TrialLedger(ledgerFile, mapper);
        this.mapper = mapper;
        this.clock = clock;
        this.warnings = warnings == null ? message -> { } : warnings;
        this.bus = bus;
        this.networking = networking;
        if (bus != null) {
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestCompleted.class, this::onCompleted));
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestFailed.class,
                    failed -> onEnded(failed.completionId(), TrialStatus.FAILED, failed.reason())));
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestAbandoned.class,
                    abandoned -> onEnded(abandoned.completionId(), TrialStatus.ABANDONED, abandoned.reason())));
        }
    }

    /**
     * Loads the ledger and deals with every run a previous server left active. Standalone: they are
     * abandoned, the player is gone and a run must never be left to hang. Network: their quests are
     * dropped here and the store hands the runs off, so each player can continue on whichever server
     * they join next.
     */
    public void start() {
        List<TrialLedger.ActiveRun> stale;
        synchronized (this) {
            try {
                ledger.load();
            } catch (IOException failure) {
                warnings.accept("Could not read " + LEDGER_FILE + "; earlier trial results are unavailable: "
                        + failure.getMessage());
            }
            stale = ledger.active();
            if (!networking.networked()) {
                for (TrialLedger.ActiveRun saved : stale) {
                    runs.put(saved.completionId(), new Run(saved.player(), saved.trialId(), saved.questId(),
                            saved.completionId(), saved.startedAt(), saved.deaths(), saved.revision(),
                            versionOf(saved.questId())));
                }
            }
        }
        if (!networking.networked()) {
            for (TrialLedger.ActiveRun saved : stale) {
                abandonRun(saved.completionId(), "the server stopped during the trial");
            }
            return;
        }
        for (TrialLedger.ActiveRun saved : stale) {
            quests.detach(saved.player(), saved.questId());
        }
        synchronized (this) {
            stale.forEach(saved -> ledger.removeActive(saved.completionId()));
            save();
        }
        networking.executor().execute(() -> {
            try {
                int released = store().releaseOwned(clock.millis());
                if (released > 0) {
                    warnings.accept("Handed off " + released + " trial run(s) this server held before it stopped; "
                            + "their players continue them on the server they join next.");
                }
                heartbeat();
            } catch (SQLException | RuntimeException failure) {
                storeFailed("release the runs this server held", failure);
            }
        });
    }

    // --- QuestTrials ---

    @Override
    public synchronized Optional<String> questFor(String trialId) {
        if (trialId == null || trialId.isBlank()) {
            return Optional.empty();
        }
        for (Map.Entry<String, QuestDefinition> entry : quests.quests().entrySet()) {
            TrialDefinition trial = entry.getValue().trial();
            if (trial != null && trial.id().equals(trialId)) {
                return Optional.of(entry.getKey());
            }
        }
        return Optional.empty();
    }

    @Override
    public synchronized Optional<String> signOff(String trialId) {
        return questFor(trialId).flatMap(this::trialOf).map(TrialDefinition::signOff);
    }

    @Override
    public CompletableFuture<TrialResult> start(UUID player, String trialId) {
        Run run;
        synchronized (this) {
            if (closed) {
                return CompletableFuture.completedFuture(
                        TrialResult.refused(trialId, player, "MysticQuests is stopping"));
            }
            Optional<Run> running = runOf(player, trialId);
            if (running.isPresent()) {
                // A double click, or a second caller: one run, one result.
                return running.get().future;
            }
            Optional<String> questId = questFor(trialId);
            if (questId.isEmpty()) {
                return CompletableFuture.completedFuture(TrialResult.refused(trialId, player,
                        "no quest package declares the trial " + trialId));
            }
            // The quest may have run before (an earlier attempt, a reset class); trials are retried by
            // the mod that owns them, so the old records must not block a new run.
            quests.clearRecords(player, questId.get());
            QuestResult started = quests.start(player, questId.get());
            Optional<Instant> since = quests.activeSince(player, questId.get());
            if (!started.success() || since.isEmpty()) {
                return CompletableFuture.completedFuture(TrialResult.refused(trialId, player,
                        started.success() ? "the trial quest did not start" : started.message()));
            }
            String completionId = QuestLifecycleEvents.completionId(player, questId.get(), since.get());
            run = new Run(player, trialId, questId.get(), completionId, since.get(), 0, 1L, versionOf(questId.get()));
            runs.put(completionId, run);
            ledger.putActive(run.saved());
            save();
        }
        if (networking.networked()) {
            networking.executor().execute(() -> claim(run));
        }
        return run.future;
    }

    @Override
    public synchronized boolean isActive(UUID player, String trialId) {
        return runOf(player, trialId).isPresent();
    }

    @Override
    public boolean cancel(UUID player, String trialId, String reason) {
        Optional<Run> run;
        synchronized (this) {
            run = runOf(player, trialId);
        }
        run.ifPresent(found -> abandonRun(found.completionId, reason == null || reason.isBlank() ? "cancelled" : reason));
        return run.isPresent();
    }

    @Override
    public synchronized Optional<TrialResult> lastResult(UUID player, String trialId) {
        return ledger.last(player, trialId);
    }

    @Override
    public synchronized Optional<TrialResult> evidence(String completionId) {
        return ledger.result(completionId);
    }

    @Override
    public synchronized Optional<TrialReceipt> receipt(String completionId) {
        return ledger.receipt(completionId);
    }

    @Override
    public Optional<String> transferHold(UUID player) {
        Optional<Run> active;
        synchronized (this) {
            active = runs.values().stream().filter(run -> run.player.equals(player)).findFirst();
        }
        if (active.isEmpty()) {
            return Optional.empty();
        }
        if (networking.networked()) {
            return Optional.of("you have the " + displayName(active.get().questId) + " trial in progress and the "
                    + "transfer did not name its target server, so the shared trial store cannot vouch for it");
        }
        return Optional.of(localHold(active.get()));
    }

    @Override
    public Optional<String> transferHold(UUID player, String targetServerId) {
        Optional<Run> active;
        synchronized (this) {
            active = runs.values().stream().filter(run -> run.player.equals(player)).findFirst();
        }
        if (active.isEmpty()) {
            return Optional.empty();
        }
        Run run = active.get();
        if (!networking.networked()) {
            return Optional.of(localHold(run));
        }
        TrialNetworkStatus status = networkStatus();
        String trial = "the " + displayName(run.questId) + " trial";
        if (!status.networkBacked()) {
            return Optional.of("you have " + trial + " in progress and the shared trial store is unavailable ("
                    + String.join("; ", status.problems()) + "); finish or abandon it first");
        }
        if (targetServerId == null || targetServerId.isBlank()) {
            return Optional.of("you have " + trial + " in progress and the target server is unknown");
        }
        if (!targetServerId.equals(networking.serverId()) && !status.hasPeer(targetServerId)) {
            return Optional.of("you have " + trial + " in progress and " + targetServerId + " does not use realm "
                    + networking.realmId() + "'s shared trial store (no recent heartbeat); finish or abandon it first");
        }
        if (!run.claimed) {
            return Optional.of("your " + trial + " is still being registered in the shared trial store; try again "
                    + "in a moment");
        }
        return Optional.empty();
    }

    private String localHold(Run run) {
        return "you have the " + displayName(run.questId) + " trial in progress on this server; "
                + "finish or abandon it first (MysticQuests trial state does not follow you to "
                + "another server)";
    }

    @Override
    public boolean networkBacked() {
        if (!networking.networked() || storeProblem != null) {
            return false;
        }
        return lastHeartbeatAt > 0 && clock.millis() - lastHeartbeatAt <= networking.leaseMillis();
    }

    @Override
    public TrialNetworkStatus networkStatus() {
        int active;
        int pending;
        synchronized (this) {
            active = runs.size();
            pending = ledger.pending().size();
        }
        if (!networking.networked()) {
            return networking.configured()
                    ? new TrialNetworkStatus(true, false, networking.realmId(), networking.serverId(), "local", 0,
                    Set.of(), active, pending, networking.problems(), clock.instant())
                    : TrialNetworkStatus.local(networking.problems());
        }
        long now = clock.millis();
        Set<String> fresh = new java.util.TreeSet<>();
        members.forEach((server, heartbeat) -> {
            if (now - heartbeat <= networking.peerStaleMillis()) {
                fresh.add(server);
            }
        });
        List<String> problems = new ArrayList<>();
        String problem = storeProblem;
        if (problem != null) {
            problems.add(problem);
        } else if (lastHeartbeatAt == 0) {
            problems.add("no heartbeat written yet");
        } else if (now - lastHeartbeatAt > networking.leaseMillis()) {
            problems.add("the last heartbeat is " + (now - lastHeartbeatAt) / 1000 + " s old");
        }
        SqlTrialStore store = store();
        return new TrialNetworkStatus(true, networkBacked(), networking.realmId(), networking.serverId(),
                store.dialect().id(), store.schemaVersion(), fresh, active, pending, problems, clock.instant());
    }

    @Override
    public void message(UUID player, String text) {
        if (player != null && text != null && !text.isBlank()) {
            quests.message(player, text);
        }
    }

    // --- Game hooks ---

    /**
     * The player left. Standalone: every run of theirs is abandoned, so nothing waits on someone who is
     * gone. Network: every run is handed off and waits for them on the server they join next.
     */
    public void onDisconnect(UUID player) {
        List<Run> theirs;
        synchronized (this) {
            theirs = runs.values().stream().filter(run -> run.player.equals(player)).toList();
        }
        awaitingResume.remove(player);
        if (!networking.networked()) {
            theirs.forEach(run -> abandonRun(run.completionId, "left the server during the trial"));
            return;
        }
        theirs.forEach(this::handOff);
    }

    /** The player joined: on a network server, the runs they left elsewhere continue here. */
    public void onJoin(UUID player) {
        if (!networking.networked()) {
            return;
        }
        synchronized (this) {
            if (closed) {
                return;
            }
        }
        networking.executor().execute(() -> resume(player));
    }

    /** The player died: counted against every trial they are running, failing those past their limit. */
    public void recordDeath(UUID player) {
        List<Run> failing = new ArrayList<>();
        List<Run> counted = new ArrayList<>();
        synchronized (this) {
            for (Run run : runs.values()) {
                if (!run.player.equals(player)) {
                    continue;
                }
                run.deaths++;
                if (!networking.networked()) {
                    run.revision++;
                }
                ledger.putActive(run.saved());
                counted.add(run);
                Optional<TrialDefinition> trial = trialOf(run.questId);
                if (trial.isPresent() && trial.get().maxDeaths() >= 0 && run.deaths > trial.get().maxDeaths()) {
                    failing.add(run);
                }
            }
            if (!counted.isEmpty()) {
                save();
            }
        }
        if (networking.networked()) {
            // A death must reach the store before the player can move on: no death resets by switching servers.
            counted.forEach(run -> networking.executor().execute(() -> checkpoint(run)));
        }
        for (Run run : failing) {
            failRun(run, "died more than " + trialOf(run.questId).map(TrialDefinition::maxDeaths).orElse(0)
                    + " time(s)");
        }
    }

    /** Runs past their time limit at {@code now}, for the runtime to fail on each player's world thread. */
    public synchronized List<String> overdue(Instant now) {
        List<String> overdue = new ArrayList<>();
        for (Run run : runs.values()) {
            int limit = trialOf(run.questId).map(TrialDefinition::timeLimitSeconds).orElse(0);
            if (limit > 0 && Duration.between(run.startedAt, now).toSeconds() >= limit) {
                overdue.add(run.completionId);
            }
        }
        return overdue;
    }

    /** The player owning an overdue run, so the runtime can reach their world thread. */
    public synchronized Optional<UUID> playerOf(String completionId) {
        Run run = runs.get(completionId);
        return run == null ? Optional.empty() : Optional.of(run.player);
    }

    /** Fails a run whose time limit has passed; nothing when it already resolved. */
    public void enforceTimeLimit(String completionId) {
        Run run;
        synchronized (this) {
            run = runs.get(completionId);
            if (run == null || !overdue(clock.instant()).contains(completionId)) {
                return;
            }
        }
        failRun(run, "ran out of time");
    }

    /**
     * Abandons every run (standalone) or hands every run off (network) and refuses new ones; called when
     * MysticQuests stops. On a network server the caller then drains the store executor, so the handoffs
     * reach the store before it closes.
     */
    @Override
    public void close() {
        List<Run> open;
        synchronized (this) {
            closed = true;
            open = List.copyOf(runs.values());
        }
        if (networking.networked()) {
            open.forEach(this::handOff);
            networking.executor().execute(() -> {
                try {
                    store().leave();
                } catch (SQLException | RuntimeException failure) {
                    warnings.accept("Could not remove this server from the shared trial store: " + failure.getMessage());
                }
            });
        } else {
            open.forEach(run -> abandonRun(run.completionId, "MysticQuests stopped"));
        }
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Unsubscribing from an in-process bus cannot fail in a way worth reporting.
            }
        }
    }

    // --- Lifecycle events (public for tests that run without a bus) ---

    public void onCompleted(QuestLifecycleEvents.QuestCompleted completed) {
        Run run;
        TrialResult result;
        synchronized (this) {
            run = runs.get(completed.completionId());
            if (run == null) {
                return;
            }
            Duration elapsed = Duration.between(run.startedAt, completed.completedAt());
            Optional<TrialDefinition> trial = trialOf(run.questId);
            int limit = trial.map(TrialDefinition::timeLimitSeconds).orElse(0);
            if (limit > 0 && elapsed.toSeconds() > limit) {
                result = resolved(run, TrialStatus.FAILED, 0.0d, false, completed.completedAt(),
                        "completed after the time limit", Map.of());
            } else {
                result = scored(run, trial.orElse(null), completed, elapsed);
            }
            finish(run, result);
        }
        deliver(run, result);
    }

    public void onEnded(String completionId, TrialStatus status, String reason) {
        Run run;
        TrialResult result;
        synchronized (this) {
            run = runs.get(completionId);
            if (run == null) {
                return;
            }
            result = resolved(run, status, 0.0d, false, clock.instant(), reason, Map.of());
            finish(run, result);
        }
        deliver(run, result);
    }

    // --- Shared trial store (R-1); every method below runs on the store executor ---

    /**
     * One heartbeat: records this server as a live member of the realm, renews the lease and saves the
     * progress of every run it owns, retries results not yet durable, retries continuations another
     * server still held, and abandons runs nobody can continue any more. The runtime schedules it every
     * third of the lease on the store executor.
     */
    public void heartbeat() {
        if (!networking.networked()) {
            return;
        }
        long now = clock.millis();
        try {
            members = Map.copyOf(store().heartbeat(now));
            lastHeartbeatAt = now;
            storeProblem = null;
        } catch (SQLException | RuntimeException failure) {
            storeFailed("write the heartbeat", failure);
            return;
        }
        List<Run> owned;
        synchronized (this) {
            owned = runs.values().stream().filter(run -> run.claimed).toList();
        }
        owned.forEach(this::checkpoint);
        retryPending();
        for (UUID player : List.copyOf(awaitingResume)) {
            if (networking.online().test(player)) {
                resume(player);
            } else {
                awaitingResume.remove(player);
            }
        }
        try {
            for (SqlTrialStore.RunRow row : store().expired(now, SWEEP_LIMIT)) {
                boolean ours;
                synchronized (this) {
                    ours = runs.containsKey(row.completionId());
                }
                if (!ours) {
                    abandonRow(row, "left the network during the trial and did not come back in time");
                }
            }
        } catch (SQLException | RuntimeException failure) {
            storeFailed("sweep expired runs", failure);
        }
    }

    private void claim(Run run) {
        SqlTrialStore.Claim claim;
        try {
            claim = store().claim(run.completionId, run.player, run.trialId, run.questId, run.questVersion,
                    run.startedAt, clock.millis());
        } catch (SQLException | RuntimeException failure) {
            storeFailed("register a trial run", failure);
            refuseClaim(run, "the shared trial store could not record the run (" + failure.getMessage() + ")");
            return;
        }
        if (!claim.claimed()) {
            refuseClaim(run, claim.reason());
            return;
        }
        synchronized (this) {
            run.revision = claim.revision();
            run.claimed = true;
            if (runs.containsKey(run.completionId)) {
                ledger.putActive(run.saved());
                save();
            }
        }
    }

    private void refuseClaim(Run run, String reason) {
        message(run.player, "&cYour trial could not start: " + reason);
        networking.playerThread().accept(run.player, () -> abandonRun(run.completionId, reason));
    }

    /** Owner checkpoint: lease, deaths and progress; a fenced run is dropped here. */
    private void checkpoint(Run run) {
        int deaths;
        synchronized (this) {
            if (!run.claimed || !runs.containsKey(run.completionId)) {
                return;
            }
            deaths = run.deaths;
        }
        long expected = run.revision;
        try {
            long next = store().checkpoint(run.completionId, expected, deaths, progress(run), clock.millis());
            if (next < 0) {
                fenced(run);
            } else {
                run.revision = next;
            }
        } catch (SQLException | RuntimeException failure) {
            storeFailed("checkpoint a trial run", failure);
        }
    }

    /** Leaves the run to its player's next server: progress saved here, quest dropped, run suspended. */
    private void handOff(Run run) {
        Optional<QuestSnapshot> snapshot;
        try {
            snapshot = quests.snapshot(run.player, run.questId);
        } catch (RuntimeException unreadable) {
            snapshot = Optional.empty();
        }
        synchronized (this) {
            if (runs.remove(run.completionId) == null) {
                return;
            }
            ledger.removeActive(run.completionId);
            save();
        }
        quests.detach(run.player, run.questId);
        run.future.complete(handedOff(run, HANDOFF_REASON));
        Optional<QuestSnapshot> progress = snapshot;
        networking.executor().execute(() -> suspend(run, progress));
    }

    private void suspend(Run run, Optional<QuestSnapshot> snapshot) {
        if (!run.claimed) {
            // Never reached the store (refused or unreachable at start): nobody can continue it.
            TrialResult result = resolved(run, TrialStatus.ABANDONED, 0.0d, false, clock.instant(),
                    "left the server before the shared trial store recorded the run", Map.of());
            synchronized (this) {
                ledger.putResult(result, receiptOf(result, run.revision + 1, false));
                save();
            }
            return;
        }
        try {
            long next = store().suspend(run.completionId, run.revision, run.deaths, json(snapshot.orElse(null)),
                    clock.millis());
            if (next < 0) {
                warnings.accept("Trial run " + run.completionId + " was taken over by another server before it "
                        + "could be handed off from this one.");
            } else {
                run.revision = next;
            }
        } catch (SQLException | RuntimeException failure) {
            // Its lease runs out instead; the next server continues from the last checkpoint.
            storeFailed("hand off a trial run", failure);
        }
    }

    /** Continues here every run of the player another server handed off or lost. */
    private void resume(UUID player) {
        long now = clock.millis();
        SqlTrialStore store = store();
        boolean waiting = false;
        try {
            List<SqlTrialStore.ResultRow> known = store.resultsOf(player, RESULT_PREFETCH);
            synchronized (this) {
                for (SqlTrialStore.ResultRow row : known) {
                    if (ledger.receipt(row.completionId()).isEmpty()) {
                        ledger.putResult(toResult(row), toReceipt(row));
                    }
                }
                if (!known.isEmpty()) {
                    save();
                }
            }
            for (SqlTrialStore.RunRow row : store.runsOf(player)) {
                synchronized (this) {
                    if (runs.containsKey(row.completionId())) {
                        continue;
                    }
                }
                if (!store.resumable(row, now)) {
                    if (SqlTrialStore.SUSPENDED.equals(row.state())) {
                        abandonRow(row, "left the network during the trial and did not come back in time");
                    } else {
                        waiting = true;
                    }
                    continue;
                }
                QuestDefinition quest = quests.quests().get(row.questId());
                if (quest == null || quest.trial() == null || !quest.trial().id().equals(row.trialId())) {
                    abandonRow(row, "this server does not have the trial quest " + row.questId());
                    continue;
                }
                String version = versionOf(row.questId());
                if (!version.equals(row.questVersion())) {
                    abandonRow(row, "the run started on version '" + row.questVersion() + "' of the trial content and "
                            + "this server has version '" + version + "'");
                    continue;
                }
                long revision = store.takeOver(row, now);
                if (revision < 0) {
                    waiting = true;
                    continue;
                }
                Run run = new Run(player, row.trialId(), row.questId(), row.completionId(), row.startedAt(),
                        row.deaths(), revision, row.questVersion());
                run.claimed = true;
                QuestSnapshot snapshot = snapshotOf(row);
                networking.playerThread().accept(player, () -> continueHere(run, snapshot));
            }
        } catch (SQLException | RuntimeException failure) {
            storeFailed("continue a player's trial runs", failure);
            waiting = true;
        }
        if (waiting) {
            awaitingResume.add(player);
        } else {
            awaitingResume.remove(player);
        }
    }

    /** On the player's thread: the quest becomes active again exactly as it was left. */
    private void continueHere(Run run, QuestSnapshot snapshot) {
        if (!networking.online().test(run.player)) {
            networking.executor().execute(() -> suspend(run, Optional.of(snapshot)));
            return;
        }
        QuestResult restored = quests.restore(run.player, run.questId, snapshot);
        synchronized (this) {
            runs.put(run.completionId, run);
            ledger.putActive(run.saved());
            save();
        }
        if (!restored.success()) {
            onEnded(run.completionId, TrialStatus.ABANDONED, "could not be continued here: " + restored.message());
            return;
        }
        message(run.player, "&6Your " + displayName(run.questId) + " trial continues here.");
    }

    /** Another server took this run over: the copy here is dropped without a result. */
    private void fenced(Run run) {
        synchronized (this) {
            if (runs.remove(run.completionId) == null) {
                return;
            }
            ledger.removeActive(run.completionId);
            save();
        }
        warnings.accept("Trial run " + run.completionId + " of " + run.player + " is owned by another server now; "
                + "this server dropped its copy.");
        networking.playerThread().accept(run.player, () -> quests.detach(run.player, run.questId));
        message(run.player, "&cYour " + displayName(run.questId) + " trial continues on another server; it ended here.");
        run.future.complete(handedOff(run, HANDOFF_REASON + " (taken over)"));
    }

    /** Resolves a stored run this server does not own as abandoned (expired, or not continuable here). */
    private void abandonRow(SqlTrialStore.RunRow row, String reason) {
        Instant now = clock.instant();
        Map<String, Double> metrics = new LinkedHashMap<>();
        Duration elapsed = Duration.between(row.startedAt(), now);
        metrics.put("elapsedSeconds", (double) Math.max(0L, elapsed.toSeconds()));
        metrics.put("deaths", (double) row.deaths());
        TrialResult result = new TrialResult(row.trialId(), row.questId(), row.questVersion(), row.playerId(),
                row.completionId(), TrialStatus.ABANDONED, 0.0d, false, elapsed, row.deaths(), metrics,
                row.startedAt(), now, reason);
        TrialReceipt receipt = receiptOf(result, row.revision() + 1, true);
        try {
            if (store().resolve(row.completionId(), row.revision(), false, toRow(result, receipt), now.toEpochMilli())) {
                synchronized (this) {
                    ledger.putResult(result, receipt);
                    save();
                }
                if (networking.online().test(row.playerId())) {
                    message(row.playerId(), "&cYour " + displayName(row.questId()) + " trial ended: " + reason + ".");
                }
            }
        } catch (SQLException | RuntimeException failure) {
            storeFailed("abandon an expired trial run", failure);
        }
    }

    /** Writes the results that could not reach the store when they resolved. */
    private void retryPending() {
        Map<String, Long> pending;
        synchronized (this) {
            pending = ledger.pending();
        }
        for (Map.Entry<String, Long> entry : pending.entrySet()) {
            Optional<TrialResult> result;
            synchronized (this) {
                result = ledger.result(entry.getKey());
            }
            if (result.isEmpty()) {
                synchronized (this) {
                    ledger.clearPending(entry.getKey());
                }
                continue;
            }
            TrialReceipt receipt = receiptOf(result.get(), entry.getValue() + 1, true);
            try {
                boolean written = store().resolve(entry.getKey(), entry.getValue(), true, toRow(result.get(), receipt),
                        clock.millis());
                synchronized (this) {
                    if (written) {
                        ledger.putResult(result.get(), receipt);
                        ledger.clearPending(entry.getKey());
                    } else {
                        warnings.accept("Trial run " + entry.getKey() + " was resolved or taken over elsewhere while "
                                + "this server could not reach the shared store; its local result is not evidence.");
                        ledger.removeResult(entry.getKey());
                    }
                    save();
                }
            } catch (SQLException | RuntimeException failure) {
                storeFailed("write a pending trial result", failure);
                return;
            }
        }
    }

    /** On the store thread: the resolved run's result reaches the store before anyone hears of it. */
    private void resolveInStore(Run run, TrialResult result) {
        if (!run.claimed) {
            synchronized (this) {
                ledger.putResult(result, receiptOf(result, run.revision + 1, false));
                save();
            }
            announce(run, result);
            return;
        }
        long expected = run.revision;
        TrialReceipt receipt = receiptOf(result, expected + 1, true);
        try {
            if (store().resolve(run.completionId, expected, true, toRow(result, receipt), clock.millis())) {
                synchronized (this) {
                    ledger.putResult(result, receipt);
                    ledger.clearPending(run.completionId);
                    save();
                }
                announce(run, result);
            } else {
                synchronized (this) {
                    ledger.removeResult(run.completionId);
                    save();
                }
                warnings.accept("Trial run " + run.completionId + " resolved here but another server owns it now; "
                        + "the result here is discarded.");
                run.future.complete(handedOff(run, HANDOFF_REASON + " (taken over)"));
            }
        } catch (SQLException | RuntimeException failure) {
            storeFailed("write a trial result", failure);
            synchronized (this) {
                ledger.markPending(run.completionId, expected);
                save();
            }
            // Told now so nobody waits; the receipt follows once the store accepts the result.
            announce(run, result);
        }
    }

    // --- internals ---

    private SqlTrialStore store() {
        SqlTrialStore store = networking.store();
        if (store == null) {
            throw new IllegalStateException("no shared trial store");
        }
        return store;
    }

    private void storeFailed(String what, Throwable failure) {
        String problem = "could not " + what + ": " + failure.getMessage();
        storeProblem = problem;
        warnings.accept("Shared trial store: " + problem);
    }

    private void abandonRun(String completionId, String reason) {
        Run run;
        synchronized (this) {
            run = runs.get(completionId);
            if (run == null) {
                return;
            }
        }
        // Abandoning the quest posts QuestAbandoned, which resolves the run through onEnded. When the
        // quest is no longer active (content reloaded without it, a crash after the quest state was
        // saved) or no bus is attached, the run is resolved here instead.
        QuestResult abandoned = quests.abandon(run.player, run.questId, reason);
        if (!abandoned.success() || bus == null) {
            onEnded(completionId, TrialStatus.ABANDONED, reason);
        }
    }

    private void failRun(Run run, String reason) {
        QuestResult failed = quests.fail(run.player, run.questId, reason);
        if (!failed.success() || bus == null) {
            onEnded(run.completionId, TrialStatus.FAILED, reason);
        }
    }

    private TrialResult scored(Run run, @Nullable TrialDefinition trial, QuestLifecycleEvents.QuestCompleted completed,
                               Duration elapsed) {
        int optionalTotal = 0;
        int optionalDone = 0;
        QuestDefinition quest = quests.quests().get(run.questId);
        if (quest != null) {
            for (ObjectiveDefinition objective : quest.objectives()) {
                if (objective.bool("optional", false)) {
                    optionalTotal++;
                    int target = Math.max(1, objective.integer("amount", 1));
                    if (completed.objectiveProgress().getOrDefault(objective.id(), 0) >= target) {
                        optionalDone++;
                    }
                }
            }
        }
        Map<String, Double> metrics = new LinkedHashMap<>();
        metrics.put("optionalObjectives", (double) optionalTotal);
        metrics.put("optionalObjectivesCompleted", (double) optionalDone);
        TrialDefinition.Ranking ranking = trial == null ? null : trial.ranking();
        if (ranking == null) {
            return resolved(run, TrialStatus.COMPLETED, 1.0d, false, completed.completedAt(), "", metrics);
        }
        metrics.put("parTimeSeconds", (double) ranking.parTimeSeconds());
        double score = ranking.score(elapsed.toSeconds(), run.deaths, optionalTotal, optionalDone);
        return resolved(run, TrialStatus.COMPLETED, score, true, completed.completedAt(), "", metrics);
    }

    private TrialResult resolved(Run run, TrialStatus status, double score, boolean ranked, Instant at,
                                 String reason, Map<String, Double> extra) {
        Duration elapsed = Duration.between(run.startedAt, at);
        Map<String, Double> metrics = new LinkedHashMap<>(extra);
        metrics.put("elapsedSeconds", (double) Math.max(0L, elapsed.toSeconds()));
        metrics.put("deaths", (double) run.deaths);
        return new TrialResult(run.trialId, run.questId, run.questVersion, run.player, run.completionId, status, score,
                ranked, elapsed, run.deaths, metrics, run.startedAt, at, reason);
    }

    /** The future result of a run that moved to another server; never saved, never posted. */
    private TrialResult handedOff(Run run, String reason) {
        Instant now = clock.instant();
        return new TrialResult(run.trialId, run.questId, run.questVersion, run.player, run.completionId,
                TrialStatus.ABANDONED, 0.0d, false, Duration.between(run.startedAt, now), run.deaths,
                Map.of("handedOff", 1.0d), run.startedAt, now, reason);
    }

    /** Under the lock: removes the run and saves its result before anyone hears of it. */
    private void finish(Run run, TrialResult result) {
        runs.remove(run.completionId);
        ledger.removeActive(run.completionId);
        if (networking.networked()) {
            // The receipt is written once the store accepted the result (resolveInStore).
            ledger.putResult(result, null);
        } else {
            ledger.putResult(result, receiptOf(result, run.revision + 1, false));
        }
        save();
    }

    /** Outside the lock: standalone results are announced now, network results once the store has them. */
    private void deliver(Run run, TrialResult result) {
        if (!networking.networked()) {
            announce(run, result);
            return;
        }
        networking.executor().execute(() -> resolveInStore(run, result));
    }

    /** Completes the future and posts {@link TrialResolved}. */
    private void announce(Run run, TrialResult result) {
        run.future.complete(result);
        if (bus != null) {
            bus.post(new TrialResolved(result));
        }
    }

    private TrialReceipt receiptOf(TrialResult result, long revision, boolean networkBacked) {
        return TrialReceipt.of(result.trialId(), result.player(), networking.realmId(), result.questId(),
                result.packageVersion(), result.completionId(), result.status(), revision, result.resolvedAt(),
                networking.serverId(), networkBacked);
    }

    private SqlTrialStore.ResultRow toRow(TrialResult result, TrialReceipt receipt) {
        return new SqlTrialStore.ResultRow(result.completionId(), receipt.evidenceId(), receipt.realmId(), result.player(),
                result.trialId(), result.questId(), result.packageVersion(), result.status(), result.score(),
                result.ranked(), result.elapsed().toMillis(), result.deaths(), json(result.metrics()), result.startedAt(),
                result.resolvedAt(), result.reason(), receipt.causalRevision(), receipt.resolvedBy(), receipt.receipt());
    }

    private TrialResult toResult(SqlTrialStore.ResultRow row) {
        Map<String, Double> metrics = Map.of();
        try {
            if (row.metrics() != null && !row.metrics().isBlank()) {
                metrics = mapper.readValue(row.metrics(), new TypeReference<Map<String, Double>>() { });
            }
        } catch (IOException | RuntimeException unreadable) {
            metrics = Map.of();
        }
        return new TrialResult(row.trialId(), row.questId(), row.questVersion(), row.playerId(), row.completionId(),
                row.status(), row.score(), row.ranked(), Duration.ofMillis(row.elapsedMillis()), row.deaths(), metrics,
                row.startedAt(), row.resolvedAt(), row.reason());
    }

    private static TrialReceipt toReceipt(SqlTrialStore.ResultRow row) {
        return new TrialReceipt(row.trialId(), row.playerId(), row.realmId(), row.questId(), row.questVersion(),
                row.evidenceId(), row.completionId(), row.status(), row.causalRevision(), row.resolvedAt(),
                row.resolvedBy(), true, row.receipt());
    }

    /** The run's progress as the store keeps it; null when it cannot be read right now. */
    @Nullable
    private String progress(Run run) {
        try {
            return quests.snapshot(run.player, run.questId).map(this::json).orElse(null);
        } catch (RuntimeException busy) {
            // Read off the world thread; a concurrent change is retried on the next checkpoint.
            return null;
        }
    }

    private QuestSnapshot snapshotOf(SqlTrialStore.RunRow row) {
        QuestSnapshot saved = null;
        try {
            if (row.progress() != null && !row.progress().isBlank()) {
                saved = mapper.readValue(row.progress(), QuestSnapshot.class);
            }
        } catch (IOException | RuntimeException unreadable) {
            warnings.accept("Trial run " + row.completionId() + " has unreadable progress; it continues from zero.");
        }
        // The start time from the run row, so the completion id stays the same.
        return new QuestSnapshot(row.startedAt(), saved == null ? Map.of() : saved.objectiveProgress(),
                saved == null ? Map.of() : saved.variables());
    }

    @Nullable
    private String json(@Nullable Object value) {
        if (value == null) {
            return null;
        }
        try {
            return mapper.writeValueAsString(value);
        } catch (IOException failure) {
            return null;
        }
    }

    private String versionOf(String questId) {
        int colon = questId.indexOf(':');
        return colon <= 0 ? "" : quests.packageVersion(questId.substring(0, colon));
    }

    private Optional<Run> runOf(UUID player, String trialId) {
        return runs.values().stream()
                .filter(run -> run.player.equals(player) && run.trialId.equals(trialId))
                .findFirst();
    }

    private Optional<TrialDefinition> trialOf(String questId) {
        QuestDefinition quest = quests.quests().get(questId);
        return quest == null ? Optional.empty() : Optional.ofNullable(quest.trial());
    }

    private String displayName(String questId) {
        QuestDefinition quest = quests.quests().get(questId);
        return quest == null ? questId : quest.displayName();
    }

    private void save() {
        try {
            ledger.save();
        } catch (IOException failure) {
            warnings.accept("Could not save " + LEDGER_FILE + ": " + failure.getMessage());
        }
    }
}
