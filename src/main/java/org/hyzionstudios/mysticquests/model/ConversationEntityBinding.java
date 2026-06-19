package org.hyzionstudios.mysticquests.model;

public final class ConversationEntityBinding {
    private String uuid;
    private String type;
    private String name;

    public String uuid() {
        return uuid;
    }

    public void setUuid(String uuid) {
        this.uuid = uuid;
    }

    public String type() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String name() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }
}
