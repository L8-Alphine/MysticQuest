package org.hyzionstudios.mysticquests.api.objective;

import java.util.List;

/**
 * One active quest as a tracker shows it (P-62 objective DTO). Plain data, already resolved for the
 * player: names are display text, progress is clamped to {@code [0, target]}.
 *
 * @param questId     {@code <package>:<quest>}
 * @param displayName the quest's name
 * @param category    the quest's category ({@code trial} for trials), empty when none
 * @param stage       the current step's name, empty when the quest has no named steps
 * @param objectives  the objectives of the current step (all of them for an ungrouped quest)
 * @param trial       whether the quest is a trial another mod started
 * @param tracked     whether this is the player's tracked quest
 * @param revision    changes whenever anything above changes: a stale-view check for actions
 */
public record TrackedQuest(
        String questId,
        String displayName,
        String category,
        String stage,
        List<Objective> objectives,
        boolean trial,
        boolean tracked,
        String revision) {

    public TrackedQuest {
        category = category == null ? "" : category;
        stage = stage == null ? "" : stage;
        objectives = objectives == null ? List.of() : List.copyOf(objectives);
    }

    /**
     * One objective.
     *
     * @param id       the objective id within the quest
     * @param label    its display text
     * @param current  progress so far
     * @param target   progress needed
     * @param complete whether it is done
     * @param optional whether it is optional (never holds the quest open)
     */
    public record Objective(String id, String label, int current, int target, boolean complete, boolean optional) {
        public Objective {
            target = Math.max(1, target);
            current = Math.max(0, Math.min(current, target));
        }
    }

    /** Objectives done in the current step. */
    public long completed() {
        return objectives.stream().filter(Objective::complete).count();
    }
}
