package org.hyzionstudios.mysticquests.service;

import java.util.Map;

public record QuestTargetContext(
        String entityId,
        String entityType,
        String entityName,
        String blockId,
        String blockType,
        String worldId,
        String volumeId,
        String volumeKey,
        String volumeWorld) {
    public static QuestTargetContext none() {
        return new QuestTargetContext(null, null, null, null, null, null, null, null, null);
    }

    public Map<String, String> entityMetadata() {
        return Map.of(
                "type", entityType == null ? "" : entityType,
                "name", entityName == null ? "" : entityName);
    }

    public Map<String, String> blockMetadata() {
        return Map.of(
                "type", blockType == null ? "" : blockType,
                "world", worldId == null ? "" : worldId);
    }

    public Map<String, String> volumeMetadata() {
        return Map.of(
                "id", volumeId == null ? "" : volumeId,
                "world", volumeWorld == null ? "" : volumeWorld);
    }
}
