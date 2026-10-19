package org.hyzionstudios.mysticquests.external;

/**
 * One objective of a mirrored quest.
 *
 * @param text the objective in the player's language
 * @param target how many are needed; 1 for a single action such as talking to someone
 */
public record ExternalObjective(String id, String text, int current, int target, boolean complete) {
    public ExternalObjective {
        id = id == null ? "" : id;
        text = text == null ? "" : text.strip();
        target = Math.max(1, target);
        current = Math.max(0, Math.min(current, target));
        complete = complete || current >= target;
    }

    public static ExternalObjective of(String id, String text, int current, int target) {
        return new ExternalObjective(id, text, current, target, false);
    }
}
