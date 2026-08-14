package org.hyzionstudios.mysticquests.model;

public final class ConversationEntityBinding {
    private String hyCitizensId;
    private String hyCitizensGroup;
    private String uuid;
    private String type;
    private String name;
    private String interactionHint;
    private boolean showPrompt = true;

    public String hyCitizensId() {
        return hyCitizensId;
    }

    public void setHyCitizensId(String hyCitizensId) {
        this.hyCitizensId = hyCitizensId;
    }

    public String hyCitizensGroup() {
        return hyCitizensGroup;
    }

    public void setHyCitizensGroup(String hyCitizensGroup) {
        this.hyCitizensGroup = hyCitizensGroup;
    }

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

    public String interactionHint() {
        return interactionHint;
    }

    public void setInteractionHint(String interactionHint) {
        this.interactionHint = interactionHint;
    }

    public boolean showPrompt() {
        return showPrompt;
    }

    public void setShowPrompt(boolean showPrompt) {
        this.showPrompt = showPrompt;
    }
}
