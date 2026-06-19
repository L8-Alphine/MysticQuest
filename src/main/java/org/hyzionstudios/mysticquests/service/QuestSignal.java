package org.hyzionstudios.mysticquests.service;

import java.util.UUID;

public record QuestSignal(
        UUID playerId,
        String type,
        String target,
        int amount,
        String world,
        double x,
        double y,
        double z,
        QuestTargetContext targetContext) {
    public static QuestSignal simple(UUID playerId, String type, String target, int amount) {
        return new QuestSignal(playerId, type, target, amount, null, 0, 0, 0, QuestTargetContext.none());
    }

    public static QuestSignal targeted(UUID playerId, String type, String target, int amount, QuestTargetContext targetContext) {
        return new QuestSignal(playerId, type, target, amount, null, 0, 0, 0, targetContext);
    }

    public static QuestSignal location(UUID playerId, String world, double x, double y, double z) {
        return new QuestSignal(playerId, "reachLocation", null, 1, world, x, y, z, QuestTargetContext.none());
    }
}
