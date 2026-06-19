package org.hyzionstudios.mysticquests.model;

import java.util.ArrayList;
import java.util.List;

public final class QuestDefinition {
    private String id;
    private String packageId;
    private String displayName;
    private String description;
    private List<ConditionDefinition> startConditions = new ArrayList<>();
    private List<ObjectiveDefinition> objectives = new ArrayList<>();
    private List<EventDefinition> startEvents = new ArrayList<>();
    private List<EventDefinition> completeEvents = new ArrayList<>();
    private List<EventDefinition> rewards = new ArrayList<>();

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String packageId() {
        return packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    public String displayName() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    public String description() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public List<ConditionDefinition> startConditions() {
        return startConditions;
    }

    public void setStartConditions(List<ConditionDefinition> startConditions) {
        this.startConditions = startConditions == null ? new ArrayList<>() : startConditions;
    }

    public List<ObjectiveDefinition> objectives() {
        return objectives;
    }

    public void setObjectives(List<ObjectiveDefinition> objectives) {
        this.objectives = objectives == null ? new ArrayList<>() : objectives;
    }

    public List<EventDefinition> startEvents() {
        return startEvents;
    }

    public void setStartEvents(List<EventDefinition> startEvents) {
        this.startEvents = startEvents == null ? new ArrayList<>() : startEvents;
    }

    public List<EventDefinition> completeEvents() {
        return completeEvents;
    }

    public void setCompleteEvents(List<EventDefinition> completeEvents) {
        this.completeEvents = completeEvents == null ? new ArrayList<>() : completeEvents;
    }

    public List<EventDefinition> rewards() {
        return rewards;
    }

    public void setRewards(List<EventDefinition> rewards) {
        this.rewards = rewards == null ? new ArrayList<>() : rewards;
    }
}
