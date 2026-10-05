package org.hyzionstudios.mysticquests.model;

import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

public final class QuestDefinition {
    private String id;
    private String packageId;
    private String displayName;
    private String description;
    private boolean startOnJoin;
    private String autoStart;
    private List<String> startTriggers = new ArrayList<>();
    private List<ConditionDefinition> startConditions = new ArrayList<>();
    private List<StageDefinition> stages = new ArrayList<>();
    private List<ObjectiveDefinition> objectives = new ArrayList<>();
    private List<EventDefinition> startEvents = new ArrayList<>();
    private List<EventDefinition> completeEvents = new ArrayList<>();
    private List<EventDefinition> rewards = new ArrayList<>();
    private Integer abandonCooldownSeconds;
    private List<ConditionDefinition> reacceptConditions = new ArrayList<>();
    /** Where the Journal files the quest: story, side, contract, guild, community, daily or your own word. */
    private String category;
    /** The rewards players are told about in the Journal; rewards that are not mentioned stay a surprise. */
    private String rewardText;
    /** How hard the board says it is, in the author's words: "Easy", "Hard", "Level 10+". */
    private String difficulty;
    /** The party size the board recommends; null when it is not a group quest. */
    private Integer partySize;
    /** What a player is told while the quest is not available yet; without it a locked quest stays off the board. */
    private String lockedText;

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

    /** The Journal category, lower case; empty when the author named none (filed under plain "Quests"). */
    public String category() {
        return category == null || category.isBlank() ? "" : category.strip().toLowerCase(java.util.Locale.ROOT);
    }

    public String rewardText() {
        return rewardText == null ? "" : rewardText.strip();
    }

    public String difficulty() {
        return difficulty == null ? "" : difficulty.strip();
    }

    /** The recommended party size, or 0 when the quest is not meant for a group. */
    public int partySize() {
        return partySize == null || partySize < 2 ? 0 : partySize;
    }

    public String lockedText() {
        return lockedText == null ? "" : lockedText.strip();
    }

    public String description() {
        return description == null ? "" : description;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public boolean startOnJoin() {
        if (startOnJoin) {
            return true;
        }
        if (isJoinTrigger(autoStart)) {
            return true;
        }
        for (String trigger : startTriggers) {
            if (isJoinTrigger(trigger)) {
                return true;
            }
        }
        return false;
    }

    public void setStartOnJoin(boolean startOnJoin) {
        this.startOnJoin = startOnJoin;
    }

    public String autoStart() {
        return autoStart;
    }

    public void setAutoStart(String autoStart) {
        this.autoStart = autoStart;
    }

    public List<String> startTriggers() {
        return startTriggers;
    }

    public void setStartTriggers(List<String> startTriggers) {
        this.startTriggers = startTriggers == null ? new ArrayList<>() : startTriggers;
    }

    public List<ConditionDefinition> startConditions() {
        return startConditions;
    }

    public void setStartConditions(List<ConditionDefinition> startConditions) {
        this.startConditions = startConditions == null ? new ArrayList<>() : startConditions;
    }

    /**
     * Optional named steps, in the order the player works through them. Objectives name one with
     * their own {@code stage} field; see {@link StageDefinition} for what happens when they name a
     * stage this list does not describe.
     */
    public List<StageDefinition> stages() {
        return stages;
    }

    public void setStages(List<StageDefinition> stages) {
        this.stages = stages == null ? new ArrayList<>() : stages;
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

    /**
     * Seconds after abandoning before this quest may be accepted again, or {@code null} when no
     * cooldown is configured.
     */
    public Integer abandonCooldownSeconds() {
        return abandonCooldownSeconds;
    }

    public void setAbandonCooldownSeconds(Integer abandonCooldownSeconds) {
        this.abandonCooldownSeconds = abandonCooldownSeconds;
    }

    /** Extra conditions that must pass before an abandoned quest may be accepted again. */
    public List<ConditionDefinition> reacceptConditions() {
        return reacceptConditions;
    }

    public void setReacceptConditions(List<ConditionDefinition> reacceptConditions) {
        this.reacceptConditions = reacceptConditions == null ? new ArrayList<>() : reacceptConditions;
    }

    /**
     * Whether abandoning this quest locks it permanently. True when the author configured neither a
     * cooldown nor re-accept conditions, which is the default.
     */
    public boolean abandonIsPermanent() {
        return abandonCooldownSeconds == null && reacceptConditions.isEmpty();
    }

    private boolean isJoinTrigger(String trigger) {
        if (trigger == null || trigger.isBlank()) {
            return false;
        }
        String normalized = trigger.toLowerCase(Locale.ROOT).replace("_", "").replace("-", "");
        return normalized.equals("join")
                || normalized.equals("playerjoin")
                || normalized.equals("playerready")
                || normalized.equals("ready");
    }
}
