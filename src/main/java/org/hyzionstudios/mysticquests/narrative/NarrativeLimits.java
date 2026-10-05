package org.hyzionstudios.mysticquests.narrative;

/**
 * Capacity limits of the narrative runtime (2.0 specification §28).
 *
 * <p>Each limit fails in the way that keeps players' progress safe:
 * <ul>
 *   <li>{@code storySessions} is a warning threshold. Refusing to open a story would strand the
 *       player mid-quest, so going over it is reported and counted, never refused.</li>
 *   <li>{@code storyEntities} is a hard cap on claimed story entities, because each one is ticked,
 *       filtered and sent to clients. A spawn or claim over it fails retryably: the transition
 *       stays incomplete and runs again later, once other stories have released theirs.</li>
 *   <li>{@code puzzleInputs} is checked when content loads: a puzzle with more inputs than this is
 *       reported as a warning.</li>
 * </ul>
 */
public record NarrativeLimits(int storySessions, int storyEntities, int puzzleInputs) {
    public static final NarrativeLimits DEFAULTS = new NarrativeLimits(5000, 1000, 64);

    public NarrativeLimits {
        if (storySessions < 1 || storyEntities < 1 || puzzleInputs < 1) {
            throw new IllegalArgumentException("narrative limits must be positive");
        }
    }
}
