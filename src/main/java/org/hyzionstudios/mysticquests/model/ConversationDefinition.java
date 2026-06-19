package org.hyzionstudios.mysticquests.model;

import java.util.ArrayList;
import java.util.List;

public final class ConversationDefinition {
    private String id;
    private String packageId;
    private String speaker;
    private String start;
    private ConversationEntityBinding entity;
    private List<ConversationNode> nodes = new ArrayList<>();

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

    public String speaker() {
        return speaker == null || speaker.isBlank() ? id : speaker;
    }

    public void setSpeaker(String speaker) {
        this.speaker = speaker;
    }

    public String start() {
        if (start != null && !start.isBlank()) {
            return start;
        }
        return nodes.isEmpty() ? null : nodes.getFirst().id();
    }

    public void setStart(String start) {
        this.start = start;
    }

    public ConversationEntityBinding entity() {
        return entity;
    }

    public void setEntity(ConversationEntityBinding entity) {
        this.entity = entity;
    }

    public List<ConversationNode> nodes() {
        return nodes;
    }

    public void setNodes(List<ConversationNode> nodes) {
        this.nodes = nodes == null ? new ArrayList<>() : nodes;
    }
}
