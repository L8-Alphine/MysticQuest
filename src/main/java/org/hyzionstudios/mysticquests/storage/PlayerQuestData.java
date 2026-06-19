package org.hyzionstudios.mysticquests.storage;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class PlayerQuestData {
    private UUID playerId;
    private Map<String, ActiveQuestData> activeQuests = new LinkedHashMap<>();
    private Map<String, Instant> completedQuests = new LinkedHashMap<>();
    private Set<String> tags = new LinkedHashSet<>();
    private Map<String, String> playerVariables = new LinkedHashMap<>();
    private Map<String, Map<String, String>> questVariables = new LinkedHashMap<>();
    private String trackedQuestId;

    public PlayerQuestData() {
    }

    public PlayerQuestData(UUID playerId) {
        this.playerId = playerId;
    }

    public UUID playerId() {
        return playerId;
    }

    public void setPlayerId(UUID playerId) {
        this.playerId = playerId;
    }

    public Map<String, ActiveQuestData> activeQuests() {
        return activeQuests;
    }

    public void setActiveQuests(Map<String, ActiveQuestData> activeQuests) {
        this.activeQuests = activeQuests == null ? new LinkedHashMap<>() : activeQuests;
    }

    public Map<String, Instant> completedQuests() {
        return completedQuests;
    }

    public void setCompletedQuests(Map<String, Instant> completedQuests) {
        this.completedQuests = completedQuests == null ? new LinkedHashMap<>() : completedQuests;
    }

    public Set<String> tags() {
        return tags;
    }

    public void setTags(Set<String> tags) {
        this.tags = tags == null ? new LinkedHashSet<>() : tags;
    }

    public Map<String, String> playerVariables() {
        return playerVariables;
    }

    public void setPlayerVariables(Map<String, String> playerVariables) {
        this.playerVariables = playerVariables == null ? new LinkedHashMap<>() : playerVariables;
    }

    public Map<String, Map<String, String>> questVariables() {
        return questVariables;
    }

    public void setQuestVariables(Map<String, Map<String, String>> questVariables) {
        this.questVariables = questVariables == null ? new LinkedHashMap<>() : questVariables;
    }

    public String trackedQuestId() {
        return trackedQuestId;
    }

    public void setTrackedQuestId(String trackedQuestId) {
        this.trackedQuestId = trackedQuestId == null || trackedQuestId.isBlank() ? null : trackedQuestId;
    }
}
