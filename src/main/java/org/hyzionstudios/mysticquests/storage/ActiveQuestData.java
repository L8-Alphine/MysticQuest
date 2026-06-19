package org.hyzionstudios.mysticquests.storage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

public final class ActiveQuestData {
    private String questId;
    private Instant startedAt = Instant.now();
    private Map<String, Integer> objectiveProgress = new LinkedHashMap<>();

    public ActiveQuestData() {
    }

    public ActiveQuestData(String questId) {
        this.questId = questId;
    }

    public String questId() {
        return questId;
    }

    public void setQuestId(String questId) {
        this.questId = questId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    public void setStartedAt(Instant startedAt) {
        this.startedAt = startedAt == null ? Instant.now() : startedAt;
    }

    public Map<String, Integer> objectiveProgress() {
        return objectiveProgress;
    }

    public void setObjectiveProgress(Map<String, Integer> objectiveProgress) {
        this.objectiveProgress = objectiveProgress == null ? new LinkedHashMap<>() : objectiveProgress;
    }
}
