package org.hyzionstudios.mysticquests.api.objective;

import java.util.UUID;

/**
 * Something a tracker shows may have changed for a player (P-62): quest progress, a start, a completion,
 * an abandonment, the tracked quest, or a content reload. Carries no quest data on purpose (hidden quests
 * stay hidden): read the new state with {@link QuestObjectives#tracked}.
 *
 * @param player whose quests changed
 * @param reason {@code quest} for a change to the player's quests, {@code reload} after a content reload
 */
public record TrackedObjectivesChanged(UUID player, String reason) {
}
