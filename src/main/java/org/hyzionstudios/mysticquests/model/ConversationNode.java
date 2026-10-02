package org.hyzionstudios.mysticquests.model;

import java.util.ArrayList;
import java.util.List;

public final class ConversationNode {
    private String id;
    private String text;
    private List<ConditionDefinition> conditions = new ArrayList<>();
    private List<ConversationChoice> choices = new ArrayList<>();
    private List<EventDefinition> events = new ArrayList<>();
    /** Optional narrative voice line played when the node is entered, e.g. {@code hyzion:old_man.warning_001}. */
    private String voice;

    public String id() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String text() {
        return text == null ? "" : text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public List<ConditionDefinition> conditions() {
        return conditions;
    }

    public void setConditions(List<ConditionDefinition> conditions) {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }

    public List<ConversationChoice> choices() {
        return choices;
    }

    public void setChoices(List<ConversationChoice> choices) {
        this.choices = choices == null ? new ArrayList<>() : choices;
    }

    public String voice() {
        return voice == null || voice.isBlank() ? null : voice.trim();
    }

    public void setVoice(String voice) {
        this.voice = voice;
    }

    public List<EventDefinition> events() {
        return events;
    }

    public void setEvents(List<EventDefinition> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }
}
