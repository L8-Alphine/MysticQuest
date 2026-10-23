package org.hyzionstudios.mysticquests.api.trial;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * How one trial run resolved.
 *
 * @param trialId      the trial id the caller asked for, e.g. {@code mysticrpg:trial/warrior}
 * @param questId      the quest that implements it, {@code <package>:<quest>}
 * @param player       who ran it
 * @param completionId the run's completion id (the same one every {@code QuestLifecycleEvents} of
 *                     the run carries); null only for {@link TrialStatus#REFUSED}, where no run began
 * @param status       completed, failed, abandoned or refused
 * @param score        normalised performance in {@code [0, 1]}; 1 for an unranked completion, 0
 *                     otherwise
 * @param ranked       whether the trial defines ranking (MysticRPG Q11); an unranked completion's
 *                     score says nothing about performance
 * @param elapsed      how long the run took
 * @param deaths       how often the player died during it
 * @param metrics      raw measurements: {@code elapsedSeconds}, {@code deaths},
 *                     {@code optionalObjectives}, {@code optionalObjectivesCompleted},
 *                     {@code parTimeSeconds} when ranked
 * @param startedAt    when the run started (null when refused)
 * @param resolvedAt   when it resolved
 * @param reason       why it failed, was abandoned or was refused; empty for a completion
 */
public record TrialResult(
        String trialId,
        String questId,
        UUID player,
        @Nullable String completionId,
        TrialStatus status,
        double score,
        boolean ranked,
        Duration elapsed,
        int deaths,
        Map<String, Double> metrics,
        @Nullable Instant startedAt,
        Instant resolvedAt,
        String reason) {

    public TrialResult {
        score = Double.isFinite(score) ? Math.clamp(score, 0.0d, 1.0d) : 0.0d;
        elapsed = elapsed == null || elapsed.isNegative() ? Duration.ZERO : elapsed;
        deaths = Math.max(0, deaths);
        metrics = metrics == null ? Map.of() : Map.copyOf(metrics);
        reason = reason == null ? "" : reason;
        questId = questId == null ? "" : questId;
    }

    /** A trial that could not start at all. */
    public static TrialResult refused(String trialId, UUID player, String reason) {
        return new TrialResult(trialId, "", player, null, TrialStatus.REFUSED, 0.0d, false, Duration.ZERO, 0,
                Map.of(), null, Instant.now(), reason);
    }

    public boolean passed() {
        return status == TrialStatus.COMPLETED;
    }

    public Optional<String> completion() {
        return Optional.ofNullable(completionId);
    }
}
