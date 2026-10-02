package org.hyzionstudios.mysticquests.model;

public final class ObjectiveDefinition extends TypedConfig {
    private String id;
    private String displayName;
    private String stage;

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String displayName() {
        return displayName == null || displayName.isBlank() ? id : displayName;
    }

    public void setDisplayName(String displayName) {
        this.displayName = displayName;
    }

    /**
     * The step this objective belongs to, or empty when the author did not group it. See
     * {@link StageDefinition}.
     *
     * <p>Instruction-style objectives ({@code "mobkill zombie 5 stage:hunt"}) reach the definition
     * through {@link TypedConfig}'s catch-all map rather than this field, so both are read here.
     */
    public String stage() {
        if (stage != null && !stage.isBlank()) {
            return stage.trim();
        }
        return text("stage", "").trim();
    }

    public void setStage(String stage) {
        this.stage = stage;
    }
}
