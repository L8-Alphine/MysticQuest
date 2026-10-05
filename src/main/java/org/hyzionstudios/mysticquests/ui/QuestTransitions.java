package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Turns two snapshots of one player's quests into the semantic moments a player should be told
 * about (Redesign Bible §6.8): a quest accepted, a new step reached, progress made, a quest
 * completed. Each is its own kind, so the notifier can give each its own treatment instead of one
 * generic toast.
 *
 * <p>Transitions come only from a diff between two snapshots the server took. The first snapshot of
 * a session is a baseline and yields nothing, so reconnecting or a server restart never replays a
 * completion — the Journal is where history lives.
 */
public final class QuestTransitions {
    private QuestTransitions() {
    }

    /** In the order a player should read them: what ended, what moved, what began. */
    public enum Kind {
        COMPLETED,
        STEP,
        PROGRESS,
        ACCEPTED
    }

    /**
     * @param detail the one line under the title: the quest name, the new step, or the objective
     *         and its count
     */
    public record Transition(Kind kind, String questId, String questTitle, String detail) {
        /**
         * Same-tag notifications replace each other in place on the client. Progress has its own tag
         * so repeated counts collapse into one live toast; every other moment of a quest shares one,
         * so "complete" replaces a still-visible "accepted" rather than stacking under it.
         */
        public String tag() {
            return "mq.quest." + questId + (kind == Kind.PROGRESS ? ".progress" : ".state");
        }
    }

    /**
     * What the diff remembers of one player.
     *
     * @param completed when each quest was last completed; a repeatable quest completed again moves
     *         its time, which is how a second completion is told apart from an old one
     */
    public record Snapshot(Map<String, JournalEntry> active, Map<String, Instant> completed) {
        public Snapshot {
            active = Map.copyOf(active);
            completed = Map.copyOf(completed);
        }

        public static Snapshot of(Collection<JournalEntry> active, Map<String, Instant> completed) {
            Map<String, JournalEntry> byId = new LinkedHashMap<>();
            for (JournalEntry entry : active) {
                byId.put(entry.questId(), entry);
            }
            return new Snapshot(byId, completed);
        }
    }

    public static List<Transition> between(Snapshot before, Snapshot after) {
        List<Transition> completed = new ArrayList<>();
        List<Transition> steps = new ArrayList<>();
        List<Transition> progress = new ArrayList<>();
        List<Transition> accepted = new ArrayList<>();

        for (Map.Entry<String, JournalEntry> previous : sorted(before.active())) {
            String questId = previous.getKey();
            Instant finishedAt = after.completed().get(questId);
            if (!after.active().containsKey(questId)
                    && finishedAt != null
                    && !finishedAt.equals(before.completed().get(questId))) {
                completed.add(new Transition(Kind.COMPLETED, questId, previous.getValue().displayName(),
                        previous.getValue().displayName()));
            }
        }

        for (Map.Entry<String, JournalEntry> current : sorted(after.active())) {
            String questId = current.getKey();
            JournalEntry now = current.getValue();
            JournalEntry then = before.active().get(questId);
            if (then == null) {
                accepted.add(new Transition(Kind.ACCEPTED, questId, now.displayName(),
                        QuestHudViewModel.from(now).objectiveText()));
                continue;
            }
            StageView stageThen = then.currentStage();
            StageView stageNow = now.currentStage();
            if (now.grouped() && stageThen != null && stageNow != null && stageNow.index() > stageThen.index()) {
                steps.add(new Transition(Kind.STEP, questId, now.displayName(),
                        stageNow.stepLabel() + "  |  " + stageNow.displayName()));
                continue;
            }
            ObjectiveView advanced = firstAdvanced(then, now);
            if (advanced != null) {
                progress.add(new Transition(Kind.PROGRESS, questId, now.displayName(),
                        advanced.displayName() + (advanced.complete() ? "  |  done" : "  " + advanced.progressLabel())));
            }
        }

        List<Transition> transitions = new ArrayList<>(completed);
        transitions.addAll(steps);
        transitions.addAll(progress);
        transitions.addAll(accepted);
        return List.copyOf(transitions);
    }

    /** The first objective, in authored order, whose count went up. */
    private static ObjectiveView firstAdvanced(JournalEntry then, JournalEntry now) {
        Map<String, Integer> before = new LinkedHashMap<>();
        for (ObjectiveView objective : then.objectives()) {
            before.put(objective.objectiveId(), objective.current());
        }
        for (ObjectiveView objective : now.objectives()) {
            Integer previous = before.get(objective.objectiveId());
            if (previous != null && objective.current() > previous) {
                return objective;
            }
        }
        return null;
    }

    /** Snapshots hold unordered maps; transitions come out in a stable order. */
    private static List<Map.Entry<String, JournalEntry>> sorted(Map<String, JournalEntry> entries) {
        return entries.entrySet().stream()
                .sorted(Map.Entry.comparingByKey())
                .filter(entry -> Objects.nonNull(entry.getValue()))
                .toList();
    }
}
