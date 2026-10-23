package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.trial.QuestTrials;
import org.hyzionstudios.mysticquests.api.trial.TrialResolved;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.QuestLifecycleEvents;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.TrialDefinition;
import org.hyzionstudios.mysticquests.service.QuestResult;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.Consumer;

/**
 * The trial check MysticRPG's class advancement asks for (ADR-012 in MysticRPG): starts a trial
 * quest, watches it through the quest lifecycle events, and resolves each run exactly once.
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
 * <p>Every public method is synchronized: trials are rare, and one lock keeps the run table, the
 * ledger and the quest state in step. Futures are completed after the lock is released.
 */
public final class QuestTrialService implements QuestTrials, AutoCloseable {

    /** Where results are saved, relative to the data folder. */
    public static final String LEDGER_FILE = "data/trials.json";

    private final TrialQuests quests;
    private final TrialLedger ledger;
    private final Clock clock;
    private final Consumer<String> warnings;
    @Nullable
    private final MysticQuestsEventBus bus;
    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    /** Runs in progress by completion id. */
    private final Map<String, Run> runs = new LinkedHashMap<>();

    private boolean closed;

    private static final class Run {
        final UUID player;
        final String trialId;
        final String questId;
        final String completionId;
        final Instant startedAt;
        int deaths;
        final CompletableFuture<TrialResult> future = new CompletableFuture<>();

        Run(UUID player, String trialId, String questId, String completionId, Instant startedAt, int deaths) {
            this.player = player;
            this.trialId = trialId;
            this.questId = questId;
            this.completionId = completionId;
            this.startedAt = startedAt;
            this.deaths = deaths;
        }

        TrialLedger.ActiveRun saved() {
            return new TrialLedger.ActiveRun(player, trialId, questId, completionId, startedAt, deaths);
        }
    }

    /**
     * @param ledgerFile {@link #LEDGER_FILE} under the data folder; null keeps results in memory
     * @param bus        where lifecycle events arrive and {@link TrialResolved} is posted; null in tests
     *                   that call the lifecycle handlers directly
     */
    public QuestTrialService(TrialQuests quests, @Nullable Path ledgerFile, ObjectMapper mapper,
                             @Nullable MysticQuestsEventBus bus, Clock clock, Consumer<String> warnings) {
        this.quests = quests;
        this.ledger = new TrialLedger(ledgerFile, mapper);
        this.clock = clock;
        this.warnings = warnings == null ? message -> { } : warnings;
        this.bus = bus;
        if (bus != null) {
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestCompleted.class, this::onCompleted));
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestFailed.class,
                    failed -> onEnded(failed.completionId(), TrialStatus.FAILED, failed.reason())));
            subscriptions.add(bus.subscribe(QuestLifecycleEvents.QuestAbandoned.class,
                    abandoned -> onEnded(abandoned.completionId(), TrialStatus.ABANDONED, abandoned.reason())));
        }
    }

    /**
     * Loads the ledger and abandons every run a previous server left active: the player is gone, and
     * a run must never be left to hang.
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
            for (TrialLedger.ActiveRun saved : stale) {
                runs.put(saved.completionId(), new Run(saved.player(), saved.trialId(), saved.questId(),
                        saved.completionId(), saved.startedAt(), saved.deaths()));
            }
        }
        for (TrialLedger.ActiveRun saved : stale) {
            abandonRun(saved.completionId(), "the server stopped during the trial");
        }
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
    public CompletableFuture<TrialResult> start(UUID player, String trialId) {
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
            Run run = new Run(player, trialId, questId.get(), completionId, since.get(), 0);
            runs.put(completionId, run);
            ledger.putActive(run.saved());
            save();
            return run.future;
        }
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
    public synchronized Optional<String> transferHold(UUID player) {
        if (networkBacked()) {
            return Optional.empty();
        }
        return runs.values().stream()
                .filter(run -> run.player.equals(player))
                .findFirst()
                .map(run -> "you have the " + displayName(run.questId) + " trial in progress on this server; "
                        + "finish or abandon it first (MysticQuests trial state does not follow you to "
                        + "another server)");
    }

    @Override
    public boolean networkBacked() {
        return false;
    }

    @Override
    public void message(UUID player, String text) {
        if (player != null && text != null && !text.isBlank()) {
            quests.message(player, text);
        }
    }

    // --- Game hooks ---

    /** The player left: every run of theirs is abandoned, so nothing waits on someone who is gone. */
    public void onDisconnect(UUID player) {
        List<String> theirs;
        synchronized (this) {
            theirs = runs.values().stream().filter(run -> run.player.equals(player))
                    .map(run -> run.completionId).toList();
        }
        theirs.forEach(completionId -> abandonRun(completionId, "left the server during the trial"));
    }

    /** The player died: counted against every trial they are running, failing those past their limit. */
    public void recordDeath(UUID player) {
        List<Run> failing = new ArrayList<>();
        synchronized (this) {
            boolean changed = false;
            for (Run run : runs.values()) {
                if (!run.player.equals(player)) {
                    continue;
                }
                run.deaths++;
                ledger.putActive(run.saved());
                changed = true;
                Optional<TrialDefinition> trial = trialOf(run.questId);
                if (trial.isPresent() && trial.get().maxDeaths() >= 0 && run.deaths > trial.get().maxDeaths()) {
                    failing.add(run);
                }
            }
            if (changed) {
                save();
            }
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

    /** Abandons every run and refuses new ones; called when MysticQuests stops. */
    @Override
    public void close() {
        List<String> open;
        synchronized (this) {
            closed = true;
            open = List.copyOf(runs.keySet());
        }
        open.forEach(completionId -> abandonRun(completionId, "MysticQuests stopped"));
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
        announce(run, result);
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
        announce(run, result);
    }

    // --- internals ---

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
        int colon = run.questId.indexOf(':');
        String packageVersion = colon <= 0 ? "" : quests.packageVersion(run.questId.substring(0, colon));
        return new TrialResult(run.trialId, run.questId, packageVersion, run.player, run.completionId, status, score, ranked,
                elapsed, run.deaths, metrics, run.startedAt, at, reason);
    }

    /** Under the lock: removes the run and saves its result before anyone hears of it. */
    private void finish(Run run, TrialResult result) {
        runs.remove(run.completionId);
        ledger.removeActive(run.completionId);
        ledger.putResult(result);
        save();
    }

    /** Outside the lock: completes the future and posts {@link TrialResolved}. */
    private void announce(Run run, TrialResult result) {
        run.future.complete(result);
        if (bus != null) {
            bus.post(new TrialResolved(result));
        }
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
