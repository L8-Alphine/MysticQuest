package org.hyzionstudios.mysticquests.narrative.diagnostic;

/**
 * Stable codes for every class of problem the narrative runtime reports.
 *
 * <p>These are the validation classes of §12.1 of the 2.0 specification plus the runtime failures
 * that sit beside them. They are codes rather than free text so the in-game debugger, logs, and
 * eventually the Web Studio can group and filter them, and so a test can assert on <em>which</em>
 * problem was found instead of matching a message.
 */
public enum DiagnosticCode {
    INVALID_ID,
    DUPLICATE_ID,
    MISSING_REFERENCE,
    UNKNOWN_TAG,
    UNKNOWN_VARIABLE,
    UNKNOWN_ACTION,
    UNKNOWN_CONDITION,
    INVALID_VARIABLE_TYPE,
    INVALID_SCOPE,
    UNSUPPORTED_SCOPE,
    /** The scope is supported but has no owner in this situation, e.g. party scope for a solo player. */
    SCOPE_UNRESOLVED,
    IMPOSSIBLE_CONDITION,
    CONTRADICTORY_CONDITION,
    REDUNDANT_CONDITION,
    INVALID_PARAMETER,
    INVALID_PUZZLE,
    INVALID_PUZZLE_OUTPUT,
    UNKNOWN_TRIGGER_VOLUME,
    UNKNOWN_ENTITY,
    MISSING_INTEGRATION,
    CIRCULAR_PROGRESSION,
    UNREACHABLE_NODE,
    NON_IDEMPOTENT_TRANSITION,
    MISSING_ASSET,
    CUTSCENE_NO_RECOVERY,
    UNTERMINATED_MEDIA,
    SCHEMA_VERSION,
    MIGRATION,
    ACTION_FAILED,
    CONDITION_FAILED
}
