package org.hyzionstudios.mysticquests.external;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * Everything one mod reports about one player at one moment.
 *
 * @param source the bridge's id
 * @param active quests in progress or waiting to be handed in, in the mod's order
 * @param tracked ids of the quests the player tracks in the mod, oldest first
 * @param completed ids of the quests the player has finished at least once
 * @param stats numbers the mod exposes for conditions, such as {@code level.mining}; empty when none
 */
public record ExternalQuestSnapshot(
        String source,
        List<ExternalQuest> active,
        List<String> tracked,
        Set<String> completed,
        Map<String, Long> stats) {

    public ExternalQuestSnapshot {
        active = List.copyOf(active);
        tracked = List.copyOf(tracked);
        completed = Set.copyOf(completed);
        stats = Map.copyOf(stats);
    }

    public static ExternalQuestSnapshot empty(String source) {
        return new ExternalQuestSnapshot(source, List.of(), List.of(), Set.of(), Map.of());
    }

    public Optional<ExternalQuest> quest(String questId) {
        return active.stream().filter(quest -> quest.questId().equals(questId)).findFirst();
    }

    public boolean isActive(String questId) {
        return quest(questId).isPresent();
    }
}
