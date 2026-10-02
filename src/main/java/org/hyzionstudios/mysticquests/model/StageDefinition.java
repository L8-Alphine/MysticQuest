package org.hyzionstudios.mysticquests.model;

/**
 * One authored step of a quest: the group its objectives are shown under, in the journal and on the
 * HUD.
 *
 * <p>Stages are presentation only. They decide what the player is shown next; they do not gate
 * progress, so an objective in a later stage still counts up when its signal arrives.
 *
 * <p>Declaring stages on the quest is optional. An objective may simply name a {@code stage} that no
 * {@link QuestDefinition#stages()} entry describes, and the step is derived from the order stages
 * first appear with a display name humanised from the id.
 */
public final class StageDefinition {
    private String id;
    private String displayName;
    private String description;

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    /** Falls back to the id, matching {@link ObjectiveDefinition#displayName()}. */
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
}
