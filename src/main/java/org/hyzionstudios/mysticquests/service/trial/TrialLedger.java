package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.trial.TrialReceipt;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The trial service's saved state, {@code data/trials.json}: runs in progress (so a crash cannot leave
 * one that never resolves) and resolved results by completion id (the evidence other mods read back).
 *
 * <p>Written whole, through a temporary file and an atomic move, on every change. Trials are rare, so
 * the file stays small; the oldest results beyond {@link #MAX_RESULTS} are dropped.
 *
 * <p>Not thread-safe on its own: {@link QuestTrialService} serialises every call.
 */
final class TrialLedger {
    static final int MAX_RESULTS = 5_000;

    /**
     * A run in progress, as saved. {@code revision} counts its saved transitions (the causal revision
     * of its receipt); 0 in files written before 2.3.0.
     */
    record ActiveRun(UUID player, String trialId, String questId, String completionId, Instant startedAt,
                     int deaths, long revision) {
    }

    /**
     * The saved form of a {@link TrialResult} and, since 2.3.0, its {@link TrialReceipt} (null fields
     * while the receipt is not known yet); Duration as milliseconds so the file stays readable.
     */
    record SavedResult(String trialId, String questId, String packageVersion, UUID player, String completionId, TrialStatus status,
                       double score, boolean ranked, long elapsedMillis, int deaths, Map<String, Double> metrics,
                       Instant startedAt, Instant resolvedAt, String reason,
                       String realmId, String evidenceId, long causalRevision, String resolvedBy, boolean networkBacked,
                       String receipt) {

        static SavedResult of(TrialResult result, @Nullable TrialReceipt receipt) {
            return new SavedResult(result.trialId(), result.questId(), result.packageVersion(), result.player(), result.completionId(),
                    result.status(), result.score(), result.ranked(), result.elapsed().toMillis(), result.deaths(),
                    result.metrics(), result.startedAt(), result.resolvedAt(), result.reason(),
                    receipt == null ? null : receipt.realmId(), receipt == null ? null : receipt.evidenceId(),
                    receipt == null ? 0L : receipt.causalRevision(), receipt == null ? null : receipt.resolvedBy(),
                    receipt != null && receipt.networkBacked(), receipt == null ? null : receipt.receipt());
        }

        Optional<TrialReceipt> toReceipt() {
            if (receipt == null || evidenceId == null) {
                return Optional.empty();
            }
            return Optional.of(new TrialReceipt(trialId, player, realmId, questId, packageVersion, evidenceId,
                    completionId, status, causalRevision, resolvedAt, resolvedBy, networkBacked, receipt));
        }

        TrialResult toResult() {
            return new TrialResult(trialId, questId, packageVersion, player, completionId, status, score, ranked,
                    Duration.ofMillis(elapsedMillis), deaths, metrics, startedAt, resolvedAt, reason);
        }
    }

    /** The file's shape. */
    static final class Document {
        int version = 1;
        List<ActiveRun> active = new ArrayList<>();
        Map<String, SavedResult> results = new LinkedHashMap<>();
        /** Results not yet durable in the shared trial store, by completion id: the revision to resolve from. */
        Map<String, Long> pending = new LinkedHashMap<>();
    }

    @Nullable
    private final Path file;
    private final ObjectMapper mapper;
    private Document document = new Document();

    /** @param file where to save; null keeps everything in memory (tests) */
    TrialLedger(@Nullable Path file, ObjectMapper mapper) {
        this.file = file;
        this.mapper = mapper;
    }

    void load() throws IOException {
        if (file == null || !Files.isRegularFile(file)) {
            return;
        }
        Document read = mapper.readValue(file.toFile(), Document.class);
        if (read != null) {
            if (read.active == null) {
                read.active = new ArrayList<>();
            }
            if (read.results == null) {
                read.results = new LinkedHashMap<>();
            }
            if (read.pending == null) {
                read.pending = new LinkedHashMap<>();
            }
            document = read;
        }
    }

    List<ActiveRun> active() {
        return List.copyOf(document.active);
    }

    void putActive(ActiveRun run) {
        document.active.removeIf(existing -> existing.completionId().equals(run.completionId()));
        document.active.add(run);
    }

    void removeActive(String completionId) {
        document.active.removeIf(existing -> existing.completionId().equals(completionId));
    }

    Optional<TrialResult> result(String completionId) {
        SavedResult saved = completionId == null ? null : document.results.get(completionId);
        return saved == null ? Optional.empty() : Optional.of(saved.toResult());
    }

    void putResult(TrialResult result) {
        putResult(result, null);
    }

    void putResult(TrialResult result, @Nullable TrialReceipt receipt) {
        if (result.completionId() == null) {
            return;
        }
        document.results.put(result.completionId(), SavedResult.of(result, receipt));
        if (document.results.size() > MAX_RESULTS) {
            document.results.values().stream()
                    .min(Comparator.comparing(SavedResult::resolvedAt))
                    .ifPresent(oldest -> document.results.remove(oldest.completionId()));
        }
    }

    Optional<TrialReceipt> receipt(String completionId) {
        SavedResult saved = completionId == null ? null : document.results.get(completionId);
        return saved == null ? Optional.empty() : saved.toReceipt();
    }

    void removeResult(String completionId) {
        document.results.remove(completionId);
        document.pending.remove(completionId);
    }

    /** Marks a result as waiting to be written to the shared store, resolving from {@code revision}. */
    void markPending(String completionId, long revision) {
        document.pending.put(completionId, revision);
    }

    void clearPending(String completionId) {
        document.pending.remove(completionId);
    }

    Map<String, Long> pending() {
        return Map.copyOf(document.pending);
    }

    /** The newest resolved run of one trial for one player. */
    Optional<TrialResult> last(UUID player, String trialId) {
        return document.results.values().stream()
                .filter(saved -> saved.player().equals(player) && saved.trialId().equals(trialId))
                .max(Comparator.comparing(SavedResult::resolvedAt))
                .map(SavedResult::toResult);
    }

    /** The newest completed run of one trial for one player, whatever came after it. */
    Optional<TrialResult> lastCompleted(UUID player, String trialId) {
        return document.results.values().stream()
                .filter(saved -> saved.player().equals(player) && saved.trialId().equals(trialId)
                        && saved.status() == TrialStatus.COMPLETED)
                .max(Comparator.comparing(SavedResult::resolvedAt))
                .map(SavedResult::toResult);
    }

    void save() throws IOException {
        if (file == null) {
            return;
        }
        Files.createDirectories(file.toAbsolutePath().getParent());
        Path temporary = file.resolveSibling(file.getFileName() + ".tmp");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), document);
        try {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException unsupported) {
            Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
        }
    }
}
