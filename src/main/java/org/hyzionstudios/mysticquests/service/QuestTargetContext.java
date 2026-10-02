package org.hyzionstudios.mysticquests.service;

import java.util.Map;

/**
 * What a trigger fired against, for actions and conditions that say {@code context}.
 *
 * <p>{@code entityId} is the live entity's UUID, which is what visibility, targeting, and entity
 * state key on. The generation fields name the same entity the way MysticGeneration does — by
 * stable identity and definition — and are populated only when the bridge recognises it. They are
 * kept separate rather than replacing {@code entityId} because the two identities address different
 * things: one is the entity in this world right now, the other survives republishing and unload.
 */
public record QuestTargetContext(
        String entityId,
        String entityType,
        String entityName,
        String blockId,
        String blockType,
        String worldId,
        String volumeId,
        String volumeKey,
        String volumeWorld,
        String generationDefinition,
        String generationUuid) {
    /** Everything but the generation identity, for triggers that cannot know one. */
    public QuestTargetContext(
            String entityId,
            String entityType,
            String entityName,
            String blockId,
            String blockType,
            String worldId,
            String volumeId,
            String volumeKey,
            String volumeWorld) {
        this(entityId, entityType, entityName, blockId, blockType, worldId, volumeId, volumeKey,
                volumeWorld, null, null);
    }

    public static QuestTargetContext none() {
        return new QuestTargetContext(null, null, null, null, null, null, null, null, null);
    }

    /** The same context, additionally naming the MysticGeneration NPC it refers to. */
    public QuestTargetContext withGeneration(String generationDefinition, String generationUuid) {
        return new QuestTargetContext(
                entityId, entityType, entityName, blockId, blockType, worldId, volumeId, volumeKey,
                volumeWorld, generationDefinition, generationUuid);
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
