package org.hyzionstudios.mysticquests.api.trial;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.UUID;

/**
 * The completion receipt of one resolved trial run: the evidence contract MysticRPG's class, era,
 * ascension and recovery commits require (MysticRPG PO addendum 2026-10-24, "Integration ownership and
 * API contracts": trialId, playerId, realmId, questVersion, evidenceId, causal revision, completion
 * receipt). Since trial contract version 2 (MysticQuests 2.3.0).
 *
 * <p>Immutable once written: a run resolves exactly once, so its receipt never changes. A consumer
 * keys its idempotency on {@link #evidenceId} (or {@link #completionId}) and can re-check
 * {@link #receipt} with {@link #digest} to detect a tampered or mis-copied record.
 *
 * @param trialId        the trial id, e.g. {@code mysticrpg:paragon_era_2}
 * @param playerId       who ran it
 * @param realmId        the realm the run belonged to (the network store's realm, or this server's
 *                       configured realm; {@code local} when none is configured)
 * @param questId        the implementing quest, {@code <package>:<quest>}
 * @param questVersion   the quest package's {@code version} when the run started
 * @param evidenceId     {@code UUIDv3("mysticquests:trial-evidence:" + realmId + "|" + completionId)}
 * @param completionId   the run's completion id (the same one its lifecycle events carry)
 * @param status         how the run resolved; only {@link TrialStatus#COMPLETED} is a pass
 * @param causalRevision the run's revision when it resolved: every claim, checkpoint, handoff and the
 *                       resolution itself raise it by one, so a higher revision is causally later
 * @param resolvedAt     when it resolved
 * @param resolvedBy     the server that resolved it
 * @param networkBacked  whether the receipt is durable in the shared trial store (R-1); false for a
 *                       receipt kept only on this server
 * @param receipt        {@link #digest} over the fields above, lower-case hex SHA-256
 */
public record TrialReceipt(
        String trialId,
        UUID playerId,
        String realmId,
        String questId,
        String questVersion,
        String evidenceId,
        String completionId,
        TrialStatus status,
        long causalRevision,
        Instant resolvedAt,
        String resolvedBy,
        boolean networkBacked,
        String receipt) {

    /** Receipt format version, part of every digest. */
    public static final String FORMAT = "mq-trial-receipt-v1";

    /** The realm id given to receipts of a server with no configured realm. */
    public static final String LOCAL_REALM = "local";

    public TrialReceipt {
        realmId = realmId == null || realmId.isBlank() ? LOCAL_REALM : realmId;
        questId = questId == null ? "" : questId;
        questVersion = questVersion == null ? "" : questVersion;
        resolvedBy = resolvedBy == null ? "" : resolvedBy;
        // Stored and digested to the millisecond, so a receipt read back from SQL equals the one written.
        resolvedAt = resolvedAt == null ? Instant.EPOCH : resolvedAt.truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
    }

    /** A receipt with its digest computed. */
    public static TrialReceipt of(String trialId, UUID playerId, String realmId, String questId, String questVersion,
                                  String completionId, TrialStatus status, long causalRevision, Instant resolvedAt,
                                  String resolvedBy, boolean networkBacked) {
        String realm = realmId == null || realmId.isBlank() ? LOCAL_REALM : realmId;
        String evidenceId = evidenceId(realm, completionId);
        return new TrialReceipt(trialId, playerId, realm, questId, questVersion, evidenceId, completionId, status,
                causalRevision, resolvedAt, resolvedBy, networkBacked,
                digest(trialId, playerId, realm, questId, questVersion, evidenceId, completionId, status,
                        causalRevision, resolvedAt));
    }

    /** The evidence id of a run in a realm. */
    public static String evidenceId(String realmId, String completionId) {
        return UUID.nameUUIDFromBytes(("mysticquests:trial-evidence:" + realmId + "|" + completionId)
                .getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** SHA-256 over the contract fields, in a fixed order; what {@link #receipt} must equal. */
    public static String digest(String trialId, UUID playerId, String realmId, String questId, String questVersion,
                                String evidenceId, String completionId, TrialStatus status, long causalRevision,
                                Instant resolvedAt) {
        String canonical = String.join("|", FORMAT, String.valueOf(trialId), String.valueOf(playerId),
                String.valueOf(realmId), String.valueOf(questId), String.valueOf(questVersion),
                String.valueOf(evidenceId), String.valueOf(completionId), String.valueOf(status),
                Long.toString(causalRevision), Long.toString(resolvedAt == null ? 0L : resolvedAt.toEpochMilli()));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by every Java platform", impossible);
        }
    }

    /** Whether {@link #receipt} matches the fields: false for a tampered or mis-copied record. */
    public boolean verifies() {
        return receipt != null && receipt.equals(digest(trialId, playerId, realmId, questId, questVersion, evidenceId,
                completionId, status, causalRevision, resolvedAt))
                && evidenceId != null && evidenceId.equals(evidenceId(realmId, completionId));
    }

    public boolean passed() {
        return status == TrialStatus.COMPLETED;
    }
}
