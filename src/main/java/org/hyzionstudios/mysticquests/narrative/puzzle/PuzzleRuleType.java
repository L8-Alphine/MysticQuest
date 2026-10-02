package org.hyzionstudios.mysticquests.narrative.puzzle;

import javax.annotation.Nullable;
import java.util.Locale;

/** How a puzzle's inputs combine into completion (§10 of the 2.0 specification). */
public enum PuzzleRuleType {
    /** Every active input. "Activate all four statues." */
    ALL,
    /** Any one active input. "Either lever opens the passage." */
    ANY,
    /** {@code required} of the active inputs. "Find 4 of 10 possible keys." */
    N_OF_M,
    /** {@code sequence}, in order; a wrong input resets progress unless told otherwise. "Fire, Water, Earth, Air." */
    SEQUENCE,
    /** Every input in {@code sequence}, in any order. "Collect four runes in any order." */
    UNORDERED_SEQUENCE,
    /** Exactly {@code required} inputs active at once, typically toggleable plates. "Exactly three plates." */
    EXACT,
    /** {@code required} inputs inside a sliding {@code window}. "Three switches within 15 seconds." */
    TIMED,
    /** Activated inputs' weights reach {@code threshold}. "Inputs contribute different power values." */
    WEIGHTED,
    /** At least {@code perGroup} activated inputs in every group. "One from each room." */
    GROUPS,
    /** Explicit states and transitions; complete on reaching a terminal state. "Multi-stage mechanism." */
    STATE_MACHINE;

    @Nullable
    public static PuzzleRuleType parse(@Nullable String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        String normalized = raw.trim().toUpperCase(Locale.ROOT).replace('-', '_');
        return switch (normalized) {
            case "N_OF_M", "NOFM", "N_OF" -> N_OF_M;
            case "UNORDERED", "UNORDERED_SEQUENCE" -> UNORDERED_SEQUENCE;
            case "STATEMACHINE", "STATE_MACHINE" -> STATE_MACHINE;
            default -> {
                try {
                    yield valueOf(normalized);
                } catch (IllegalArgumentException unknown) {
                    yield null;
                }
            }
        };
    }
}
