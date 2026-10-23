package org.hyzionstudios.mysticquests.api.trial;

import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

/**
 * Trials another mod starts and awaits: MysticRPG's class advancement trials (ADR-012 in MysticRPG).
 * Reached through {@code MysticQuestsApi.get().trials()}.
 *
 * <p>A trial is an ordinary quest whose definition carries a {@code trial} section naming the trial
 * id. Every run resolves exactly once, as {@link TrialStatus#COMPLETED}, {@link TrialStatus#FAILED}
 * or {@link TrialStatus#ABANDONED}: a player who disconnects abandons it, a server stop abandons it,
 * a time limit or death limit fails it. The future returned by {@link #start} therefore always
 * completes; it never completes exceptionally.
 *
 * <p>Results and the completion evidence are kept in MysticQuests' data folder
 * ({@code data/trials.json}), keyed by completion id, so a result reported while the caller could
 * not record it can be read again with {@link #evidence}.
 *
 * <p><b>Network.</b> MysticQuests 2.1 keeps trial state on the local server ({@link #networkBacked()}
 * is false), so a player with an active trial must not be transferred to another server:
 * {@link #transferHold} says why, for the transferring mod to refuse or hold the transfer.
 *
 * <p>Futures complete on the thread that resolved the run, usually the world thread; callers that do
 * blocking work on completion should hop to their own executor.
 */
public interface QuestTrials {

    /** Version of this contract. Version 1: MysticQuests 2.1.0. */
    int API_VERSION = 1;

    default int apiVersion() {
        return API_VERSION;
    }

    /** The quest implementing a trial id, {@code <package>:<quest>}; empty when no content declares it. */
    Optional<String> questFor(String trialId);

    /**
     * Starts a run of the trial for an online player, or returns the run already in progress (a
     * double click starts one run). A trial completed, failed or abandoned before may be run again:
     * retry policy belongs to the caller.
     *
     * @return completes once with the result; {@link TrialStatus#REFUSED} at once when no run began
     */
    CompletableFuture<TrialResult> start(UUID player, String trialId);

    /** Whether the player has a run of this trial in progress. */
    boolean isActive(UUID player, String trialId);

    /**
     * Abandons the player's run of this trial (its quest is abandoned too).
     *
     * @return false when no run was in progress
     */
    boolean cancel(UUID player, String trialId, String reason);

    /** The player's most recent resolved run of this trial, if any is kept. */
    Optional<TrialResult> lastResult(UUID player, String trialId);

    /** The resolved run with this completion id: replay-safe completion evidence (request Q-1). */
    Optional<TrialResult> evidence(String completionId);

    /**
     * Why the player may not be transferred to another server right now, or empty when they may.
     * While {@link #networkBacked()} is false, any active trial holds the player here (ADR-012 point 3).
     */
    Optional<String> transferHold(UUID player);

    /** Whether trial state is shared with other servers (request Q-12); false in MysticQuests 2.1. */
    boolean networkBacked();

    /** Tells the player something about their trial in chat; nothing when they are offline. */
    void message(UUID player, String text);
}
