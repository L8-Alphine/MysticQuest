package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
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

    /** A run in progress, as saved. */
    record ActiveRun(UUID player, String trialId, String questId, String completionId, Instant startedAt,
                     int deaths) {
    }

    /** The saved form of a {@link TrialResult}; Duration as milliseconds so the file stays readable. */
    record SavedResult(String trialId, String questId, UUID player, String completionId, TrialStatus status,
                       double score, boolean ranked, long elapsedMillis, int deaths, Map<String, Double> metrics,
                       Instant startedAt, Instant resolvedAt, String reason) {

        static SavedResult of(TrialResult result) {
            return new SavedResult(result.trialId(), result.questId(), result.player(), result.completionId(),
                    result.status(), result.score(), result.ranked(), result.elapsed().toMillis(), result.deaths(),
                    result.metrics(), result.startedAt(), result.resolvedAt(), result.reason());
        }

        TrialResult toResult() {
            return new TrialResult(trialId, questId, player, completionId, status, score, ranked,
                    Duration.ofMillis(elapsedMillis), deaths, metrics, startedAt, resolvedAt, reason);
        }
    }

    /** The file's shape. */
    static final class Document {
        int version = 1;
        List<ActiveRun> active = new ArrayList<>();
        Map<String, SavedResult> results = new LinkedHashMap<>();
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
        if (result.completionId() == null) {
            return;
        }
        document.results.put(result.completionId(), SavedResult.of(result));
        if (document.results.size() > MAX_RESULTS) {
            document.results.values().stream()
                    .min(Comparator.comparing(SavedResult::resolvedAt))
                    .ifPresent(oldest -> document.results.remove(oldest.completionId()));
        }
    }

    /** The newest resolved run of one trial for one player. */
    Optional<TrialResult> last(UUID player, String trialId) {
        return document.results.values().stream()
                .filter(saved -> saved.player().equals(player) && saved.trialId().equals(trialId))
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
