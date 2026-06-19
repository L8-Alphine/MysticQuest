package org.hyzionstudios.mysticquests.service;

public record QuestResult(boolean success, String message) {
    public static QuestResult success(String message) {
        return new QuestResult(true, message);
    }

    public static QuestResult failure(String message) {
        return new QuestResult(false, message);
    }
}
