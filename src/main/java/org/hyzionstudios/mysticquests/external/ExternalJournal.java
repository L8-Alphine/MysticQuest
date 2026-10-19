package org.hyzionstudios.mysticquests.external;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;

import java.util.ArrayList;
import java.util.List;

/**
 * Mirrored quests in the shape MysticQuests' Journal and HUD already draw, so they need no markup of
 * their own and read like any other quest.
 */
public final class ExternalJournal {
    /** Objective id of the synthetic "hand it in" line, so the HUD leads with what the player must do. */
    static final String NEXT_ACTION_ID = "mysticquests:next_action";

    private ExternalJournal() {
    }

    /**
     * The quest as a Journal entry under its {@link ExternalQuest#key() key}. A quest waiting to be
     * handed in gets its next action as a last open objective, which is what the tracker then leads
     * with; a mod that pays out by itself has none, and its finished quest leaves the book.
     */
    public static JournalEntry entry(ExternalQuest quest) {
        List<ObjectiveView> objectives = new ArrayList<>();
        for (ExternalObjective objective : quest.objectives()) {
            ObjectiveView view = ObjectiveView.of(
                    objective.id().isEmpty() ? "objective" : objective.id(), objective.text(),
                    objective.complete() ? objective.target() : objective.current(), objective.target());
            objectives.add(view);
        }
        if (quest.status() == ExternalQuest.Status.READY && !quest.nextAction().isEmpty()) {
            objectives.add(ObjectiveView.of(NEXT_ACTION_ID, quest.nextAction(), 0, 1));
        }
        boolean complete = quest.status() == ExternalQuest.Status.READY && quest.nextAction().isEmpty();
        if (objectives.isEmpty()) {
            objectives.add(ObjectiveView.note(complete ? "Ready to claim" : "In progress"));
        }
        StageView stage = quest.stepCount() > 1 || !quest.stepName().isEmpty()
                ? new StageView(stageId(quest), stageName(quest), quest.stepIndex(), quest.stepCount(), List.copyOf(objectives))
                : StageView.single(objectives);
        return new JournalEntry(quest.key(), quest.title(), quest.description(), List.copyOf(objectives), List.of(stage), complete);
    }

    /**
     * A finished mirrored quest as a Journal entry under its key, read the way MysticQuests' own
     * finished quests are: title and a "Completed" line. The mods report nothing else about it.
     */
    public static JournalEntry finishedEntry(ExternalQuestBook.Finished quest) {
        return JournalEntry.ungrouped(quest.key(), quest.title(), "", List.of(ObjectiveView.note("Completed")), true);
    }

    private static String stageId(ExternalQuest quest) {
        return "step_" + quest.stepIndex();
    }

    private static String stageName(ExternalQuest quest) {
        return quest.stepName().isEmpty() ? "Step " + quest.stepIndex() : quest.stepName();
    }
}
