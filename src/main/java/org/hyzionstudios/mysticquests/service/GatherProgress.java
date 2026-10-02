package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;

import java.util.function.ToIntFunction;

/**
 * {@code gather} objectives count what the player is holding, the way Hytale's own gather tasks do
 * ({@code GatherObjectiveTask}): progress is the number of matching items in the inventory, capped
 * at the objective's {@code amount}, recomputed whenever the inventory changes.
 *
 * <p>The engine has no event for picking an item up off the ground, and counting held items covers
 * every way of getting one anyway: picking up, crafting, trading, rewards. Dropping or using items
 * lowers progress again until the quest completes.
 */
public final class GatherProgress {
    public static final String TYPE = "gather";

    private GatherProgress() {
    }

    /** The item a gather objective counts: {@code target}, or {@code item} as written by older content. */
    public static String item(ObjectiveDefinition objective) {
        return objective.text("target", objective.text("item", "")).trim();
    }

    public static boolean has(QuestDefinition quest) {
        return quest.objectives().stream().anyMatch(objective -> TYPE.equals(objective.type()) && !item(objective).isEmpty());
    }

    /**
     * Sets each gather objective of one active quest to what the player holds.
     *
     * @param held how many of an item id the player is holding
     * @return whether any progress changed
     */
    public static boolean sync(QuestDefinition quest, ActiveQuestData active, ToIntFunction<String> held) {
        boolean changed = false;
        for (ObjectiveDefinition objective : quest.objectives()) {
            String item = item(objective);
            if (!TYPE.equals(objective.type()) || item.isEmpty()) {
                continue;
            }
            int target = Math.max(1, objective.integer("amount", 1));
            int value = Math.max(0, Math.min(target, held.applyAsInt(item)));
            Integer previous = active.objectiveProgress().put(objective.id(), value);
            if (previous == null || previous != value) {
                changed = true;
            }
        }
        return changed;
    }
}
