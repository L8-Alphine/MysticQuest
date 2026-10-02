package org.hyzionstudios.mysticquests.model;

/**
 * Which entity a conversation belongs to.
 *
 * <p>Every field is an alternative way of naming the same thing, tried in the order the matching
 * code documents. The generation fields are the durable ones: {@code uuid}, {@code type}, and
 * {@code name} all describe a live entity, and a MysticGeneration NPC gets a new entity UUID every
 * time its definition is republished, so content pinned that way silently stops matching.
 */
public final class ConversationEntityBinding {
    private String hyCitizensId;
    private String hyCitizensGroup;
    private String generationDefinition;
    private String generationUuid;
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

    /** A MysticGeneration definition id, e.g. {@code hyzion:avalon_guard}. Matches every NPC of it. */
    public String generationDefinition() {
        return generationDefinition;
    }

    public void setGenerationDefinition(String generationDefinition) {
        this.generationDefinition = generationDefinition;
    }

    /** One MysticGeneration NPC by its stable identity, which survives republishing and unload. */
    public String generationUuid() {
        return generationUuid;
    }

    public void setGenerationUuid(String generationUuid) {
        this.generationUuid = generationUuid;
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
