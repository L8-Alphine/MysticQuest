package org.hyzionstudios.mysticquests.service;

import java.util.List;

public record JournalEntry(
        String questId,
        String displayName,
        String description,
        List<String> objectives,
        boolean complete) {
}
