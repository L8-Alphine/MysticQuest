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
 * <p><b>Network.</b> A standalone server keeps trial state on the local server ({@link #networkBacked()}
 * is false), so a player with an active trial must not be transferred to another server:
 * {@link #transferHold} says why, for the transferring mod to refuse or hold the transfer. With the
 * shared-SQL trial store (2.3.0, {@code trials.network}) a run follows its player: the server they
 * leave hands it off, the server they join continues it, and one server runs it at a time.
 *
 * <p>Futures complete on the thread that resolved the run, usually the world thread; callers that do
 * blocking work on completion should hop to their own executor.
 */
public interface QuestTrials {

    /**
     * Version of this contract. Version 1: MysticQuests 2.1.0. Version 2 (2.3.0, MysticRPG PO addendum
     * R-1): {@link #receipt}, {@link #transferHold(UUID, String)} and {@link #networkStatus()}, the
     * shared-SQL trial store and cross-server continuation.
     */
    int API_VERSION = 2;

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

    /**
     * Whether trial state is shared with other servers right now (request Q-12, R-1): the shared-SQL
     * store is configured, migrated and healthy. False on a standalone server.
     */
    boolean networkBacked();

    /**
     * The completion receipt of a resolved run (contract fields trialId, playerId, realmId,
     * questVersion, evidenceId, causal revision, receipt). Empty when no resolved run with that id is
     * known here; on a network server the player's receipts are loaded when they join, and a run
     * resolved here is known at once. Since version 2.
     */
    default Optional<TrialReceipt> receipt(String completionId) {
        return Optional.empty();
    }

    /**
     * Why the player may not be transferred to {@code targetServerId} right now, or empty when they
     * may. With an active trial, a transfer is allowed only when this server and the target both use
     * the healthy shared trial store of the same realm; otherwise the reason names what is missing.
     * Since version 2; version 1 consumers call {@link #transferHold(UUID)}.
     */
    default Optional<String> transferHold(UUID player, String targetServerId) {
        return transferHold(player);
    }

    /**
     * The content sign-off state the trial's quest declares ({@code trial.signOff}, upper case, e.g.
     * {@code PENDING}); empty when no content declares the trial or it states none. Provisional trial
     * content (MysticRPG P-47, P-48) says {@code PENDING} until the product owner signs it. Since version 2.
     */
    default Optional<String> signOff(String trialId) {
        return Optional.empty();
    }

    /** Where trial state lives and how healthy the shared store is (R-1 diagnostics). Since version 2. */
    default TrialNetworkStatus networkStatus() {
        return TrialNetworkStatus.local(java.util.List.of());
    }

    /** Tells the player something about their trial in chat; nothing when they are offline. */
    void message(UUID player, String text);
}
