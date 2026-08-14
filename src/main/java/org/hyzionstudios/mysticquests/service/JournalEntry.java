package org.hyzionstudios.mysticquests.service;

import java.util.List;

public record JournalEntry(
        String questId,
        String displayName,
        String description,
        List<ObjectiveView> objectives,
        boolean complete) {

    public int completedObjectiveCount() {
        return (int) objectives.stream().filter(ObjectiveView::complete).count();
    }

    /** {@code "2 / 5 objectives"} for card and row summaries. */
    public String progressSummary() {
        return completedObjectiveCount() + " / " + objectives.size() + " objectives";
    }
}
