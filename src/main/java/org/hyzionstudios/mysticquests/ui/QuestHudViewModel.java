package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;

import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Semantic snapshot consumed by the tracked-quest HUD.
 *
 * <p>This is the first presentation boundary for the 2.0 redesign: markup receives labels and
 * values that already have player-facing meaning instead of reconstructing quest state. The
 * narrative runtime can replace {@link #from(JournalEntry)} with its revisioned snapshot later
 * without changing the HUD document. Puzzle information is a separate layer with its own model,
 * {@link QuestPuzzleHudState}, because it is shown whether or not a quest is tracked.
 *
 * @param primaryObjectiveId the objective the HUD leads with, or empty for a synthetic note; the
 *         HUD service looks up its map marker by this id
 * @param otherTracked the player's other tracked quests, shown under this one in the expanded
 *         composition; at most {@link #OTHER_TRACKED_LIMIT}, never this quest
 */
public record QuestHudViewModel(
        DisplayMode displayMode,
        PriorityClass priorityClass,
        String categoryLabel,
        String stateLabel,
        String questId,
        String questTitle,
        String contextLabel,
        String primaryObjectiveId,
        String objectiveText,
        String objectiveProgressLabel,
        float objectiveProgressRatio,
        String questProgressLabel,
        String guidanceLabel,
        List<ObjectiveRow> supportingObjectives,
        String actionHint,
        List<TrackedRow> otherTracked) {

    /** Other tracked quests the expanded composition declares rows for. */
    public static final int OTHER_TRACKED_LIMIT = 3;

    public enum DisplayMode {
        COMPACT,
        EXPANDED
    }

    /** Where this snapshot ranks against other HUD surfaces (Redesign Bible §5.1). */
    public enum PriorityClass {
        /** All objectives are done: the next step is the player's to take. */
        ACTION_REQUIRED,
        ACTIVE_PRIMARY
    }

    /** One secondary row in the expanded tracker; the primary objective keeps its own treatment. */
    public record ObjectiveRow(
            String roleLabel,
            String objectiveText,
            String progressLabel,
            float progressRatio,
            boolean complete) {
    }

    /**
     * Another quest the player tracks, as one compact row under the tracked quest: its title and its
     * current objective. This is what the trackers of the mods MysticQuests mirrors listed before
     * MysticQuests' tracker replaced them.
     *
     * @param progressLabel the current objective's count, such as {@code "2 / 5"}; empty when it has none
     */
    public record TrackedRow(
            String questId,
            String categoryLabel,
            String questTitle,
            String objectiveText,
            String progressLabel) {

        /** The quest with the objective the tracker would lead with if it showed this quest. */
        public static TrackedRow of(JournalEntry entry, String categoryLabel) {
            ObjectiveView current = primaryObjective(entry, currentObjectives(entry));
            return new TrackedRow(entry.questId(), categoryLabel, entry.displayName(),
                    current.displayName(), current.progressLabel());
        }
    }

    public QuestHudViewModel {
        supportingObjectives = List.copyOf(supportingObjectives);
        otherTracked = List.copyOf(otherTracked);
    }

    public static QuestHudViewModel from(JournalEntry entry) {
        StageView stage = entry.currentStage();
        List<ObjectiveView> objectives = currentObjectives(entry);
        ObjectiveView primary = primaryObjective(entry, objectives);

        String context = entry.grouped() && stage != null
                ? stage.stepLabel() + "  |  " + stage.displayName()
                : "TRACKED QUEST";
        int supporting = (int) objectives.stream().filter(objective -> !objective.complete()).count();
        if (!primary.complete() && !primary.objectiveId().isEmpty()) {
            supporting--;
        }

        String guidance;
        if (entry.complete()) {
            guidance = "All objectives complete";
        } else if (supporting > 0) {
            guidance = supporting + (supporting == 1
                    ? " supporting objective in this step"
                    : " supporting objectives in this step");
        } else if (stage != null && stage.index() < stage.total()) {
            guidance = "Complete this objective to advance the story";
        } else {
            guidance = "Open the Journal for quest details";
        }

        float ratio = primary.objectiveId().isEmpty()
                ? (entry.complete() ? 1.0f : 0.0f)
                : (float) primary.current() / (float) Math.max(1, primary.target());

        List<ObjectiveRow> supportingRows = objectives.stream()
                .filter(objective -> objective != primary)
                .sorted((left, right) -> Boolean.compare(left.complete(), right.complete()))
                .limit(3)
                .map(objective -> new ObjectiveRow(
                        objective.complete() ? "COMPLETE" : "SUPPORTING",
                        objective.displayName(),
                        objective.progressLabel(),
                        (float) objective.current() / (float) Math.max(1, objective.target()),
                        objective.complete()))
                .toList();

        return new QuestHudViewModel(
                DisplayMode.COMPACT,
                entry.complete() ? PriorityClass.ACTION_REQUIRED : PriorityClass.ACTIVE_PRIMARY,
                entry.complete() ? "COMPLETE" : "QUEST",
                entry.complete() ? "DONE" : "TRACKED",
                entry.questId(),
                entry.displayName(),
                context,
                primary.objectiveId(),
                primary.displayName(),
                primary.progressLabel(),
                ratio,
                "QUEST " + entry.progressLabel(),
                guidance,
                supportingRows,
                "OPEN JOURNAL  /journal",
                List.of());
    }

    /** The current step's objectives, or every objective when the quest has no steps. */
    private static List<ObjectiveView> currentObjectives(JournalEntry entry) {
        StageView stage = entry.currentStage();
        return stage == null ? entry.objectives() : stage.objectives();
    }

    /** The first open objective, else the last one, else a note saying where the quest stands. */
    private static ObjectiveView primaryObjective(JournalEntry entry, List<ObjectiveView> objectives) {
        return objectives.stream()
                .filter(objective -> !objective.complete())
                .findFirst()
                .orElseGet(() -> objectives.isEmpty()
                        ? ObjectiveView.note(entry.complete() ? "Quest complete" : "Awaiting objective")
                        : objectives.getLast());
    }

    public QuestHudViewModel withDisplayMode(DisplayMode mode) {
        return new QuestHudViewModel(
                mode, priorityClass, categoryLabel, stateLabel, questId, questTitle, contextLabel,
                primaryObjectiveId, objectiveText, objectiveProgressLabel, objectiveProgressRatio,
                questProgressLabel, guidanceLabel, supportingObjectives, actionHint, otherTracked);
    }

    /** The same model with the quest's category on its badge; a finished quest keeps its COMPLETE badge. */
    public QuestHudViewModel withCategory(String category) {
        if (priorityClass == PriorityClass.ACTION_REQUIRED) {
            return this;
        }
        return new QuestHudViewModel(
                displayMode, priorityClass, category, stateLabel, questId, questTitle, contextLabel,
                primaryObjectiveId, objectiveText, objectiveProgressLabel, objectiveProgressRatio,
                questProgressLabel, guidanceLabel, supportingObjectives, actionHint, otherTracked);
    }

    /** Replaces the guidance line, for instance once the primary objective has a map marker. */
    public QuestHudViewModel withGuidance(String guidance) {
        return new QuestHudViewModel(
                displayMode, priorityClass, categoryLabel, stateLabel, questId, questTitle, contextLabel,
                primaryObjectiveId, objectiveText, objectiveProgressLabel, objectiveProgressRatio,
                questProgressLabel, guidance, supportingObjectives, actionHint, otherTracked);
    }

    /**
     * The same model with the player's other tracked quests under it, in the order given. This quest
     * and repeats are left out, and only the first {@link #OTHER_TRACKED_LIMIT} are kept.
     */
    public QuestHudViewModel withOtherTracked(List<TrackedRow> rows) {
        Set<String> seen = new HashSet<>();
        seen.add(questId);
        List<TrackedRow> kept = rows.stream()
                .filter(row -> seen.add(row.questId()))
                .limit(OTHER_TRACKED_LIMIT)
                .toList();
        return new QuestHudViewModel(
                displayMode, priorityClass, categoryLabel, stateLabel, questId, questTitle, contextLabel,
                primaryObjectiveId, objectiveText, objectiveProgressLabel, objectiveProgressRatio,
                questProgressLabel, guidanceLabel, supportingObjectives, actionHint, kept);
    }
}
