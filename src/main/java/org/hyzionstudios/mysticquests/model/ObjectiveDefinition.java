package org.hyzionstudios.mysticquests.model;

public final class ObjectiveDefinition extends TypedConfig {
    private String id;
    private String displayName;

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
}
