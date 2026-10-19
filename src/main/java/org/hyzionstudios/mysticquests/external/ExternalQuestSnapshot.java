package org.hyzionstudios.mysticquests.external;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.HashMap;
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
 * @param completions what the mod says about finished quests, by id; entries for ids not in
 *         {@code completed} are dropped, and a finished quest missing here reads as its id
 * @param stats numbers the mod exposes for conditions, such as {@code level.mining}; empty when none
 */
public record ExternalQuestSnapshot(
        String source,
        List<ExternalQuest> active,
        List<String> tracked,
        Set<String> completed,
        Map<String, Completion> completions,
        Map<String, Long> stats) {

    /**
     * One finished quest, as the Journal's completed section lists it.
     *
     * @param title the quest's title in the player's language
     * @param at when the player finished it, or null when the mod does not record that
     */
    public record Completion(String title, @Nullable Instant at) {
        public Completion {
            title = title == null ? "" : title.strip();
        }
    }

    public ExternalQuestSnapshot {
        active = List.copyOf(active);
        tracked = List.copyOf(tracked);
        completed = Set.copyOf(completed);
        Map<String, Completion> known = new HashMap<>(completions);
        known.keySet().retainAll(completed);
        completions = Map.copyOf(known);
        stats = Map.copyOf(stats);
    }

    /** A snapshot that knows its finished quests by id only. */
    public ExternalQuestSnapshot(String source, List<ExternalQuest> active, List<String> tracked,
                                 Set<String> completed, Map<String, Long> stats) {
        this(source, active, tracked, completed, Map.of(), stats);
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

    /** A finished quest as the Journal shows it: the mod's title and date, or its id when the mod gave no title. */
    public Completion completion(String questId) {
        Completion known = completions.get(questId);
        if (known == null) {
            return new Completion(questId, null);
        }
        return known.title().isEmpty() ? new Completion(questId, known.at()) : known;
    }
}
