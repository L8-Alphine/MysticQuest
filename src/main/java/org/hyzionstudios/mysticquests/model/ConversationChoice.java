package org.hyzionstudios.mysticquests.model;

import java.util.ArrayList;
import java.util.List;

public final class ConversationChoice {
    private String text;
    private String next;
    private List<ConditionDefinition> conditions = new ArrayList<>();
    private List<EventDefinition> events = new ArrayList<>();

    public String text() {
        return text == null ? "" : text;
    }

    public void setText(String text) {
        this.text = text;
    }

    public String next() {
        return next;
    }

    public void setNext(String next) {
        this.next = next;
    }

    public List<ConditionDefinition> conditions() {
        return conditions;
    }

    public void setConditions(List<ConditionDefinition> conditions) {
        this.conditions = conditions == null ? new ArrayList<>() : conditions;
    }

    public List<EventDefinition> events() {
        return events;
    }

    public void setEvents(List<EventDefinition> events) {
        this.events = events == null ? new ArrayList<>() : events;
    }
}
