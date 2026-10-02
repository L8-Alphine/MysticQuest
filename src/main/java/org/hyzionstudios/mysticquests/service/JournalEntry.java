package org.hyzionstudios.mysticquests.service;

import java.util.List;

/**
 * One quest as a UI surface sees it.
 *
 * @param objectives every objective, in authored order
 * @param stages the same objectives grouped into steps; never empty while {@code objectives} is not
 */
public record JournalEntry(
        String questId,
        String displayName,
        String description,
        List<ObjectiveView> objectives,
        List<StageView> stages,
        boolean complete) {

    /** Entry whose objectives were never grouped into steps — one implicit step holds them all. */
    public static JournalEntry ungrouped(
            String questId,
            String displayName,
            String description,
            List<ObjectiveView> objectives,
            boolean complete) {
        return new JournalEntry(
                questId, displayName, description, objectives, List.of(StageView.single(objectives)), complete);
    }

    public int completedObjectiveCount() {
        return (int) objectives.stream().filter(ObjectiveView::complete).count();
    }

    /** {@code "2 / 5 objectives"} for card and row summaries. */
    public String progressSummary() {
        return completedObjectiveCount() + " / " + objectives.size() + " objectives";
    }

    /** {@code "2 / 5"} — the same count without the noun, for surfaces that label it themselves. */
    public String progressLabel() {
        return completedObjectiveCount() + " / " + objectives.size();
    }

    /** Share of objectives done, for a meter. */
    public float progressRatio() {
        return objectives.isEmpty() ? 0f : (float) completedObjectiveCount() / (float) objectives.size();
    }

    /**
     * Whether the author grouped this quest into named steps. A quest with a single unnamed step is
     * not grouped, and surfaces show its objectives without step furniture.
     */
    public boolean grouped() {
        return stages.size() > 1 || (stages.size() == 1 && stages.getFirst().named());
    }

    /**
     * The step the player is working on: the first with an objective still open, or the last step
     * once everything is done. Null only when the quest has no objectives at all.
     */
    public StageView currentStage() {
        if (stages.isEmpty()) {
            return null;
        }
        return stages.stream()
                .filter(stage -> !stage.complete())
                .findFirst()
                .orElse(stages.getLast());
    }
}
