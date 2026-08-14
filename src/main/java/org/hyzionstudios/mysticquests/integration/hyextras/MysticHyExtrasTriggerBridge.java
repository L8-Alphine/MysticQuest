package org.hyzionstudios.mysticquests.integration.hyextras;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.service.ScopedStateService;

import com.fasterxml.jackson.databind.node.LongNode;
import com.fasterxml.jackson.databind.node.TextNode;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.UUID;
import java.util.logging.Level;

public final class MysticHyExtrasTriggerBridge {
    private static ScopedStateService scopedStateService;
    private static PlayerQuestService questService;
    private static HytaleLogger logger;

    private MysticHyExtrasTriggerBridge() {
    }

    public static void initialize(ScopedStateService scopedStateService, PlayerQuestService questService, HytaleLogger logger) {
        MysticHyExtrasTriggerBridge.scopedStateService = scopedStateService;
        MysticHyExtrasTriggerBridge.questService = questService;
        MysticHyExtrasTriggerBridge.logger = logger;
    }

    public static boolean ready() {
        return scopedStateService != null;
    }

    public static boolean hasTag(TriggerContext context, MysticTriggerCondition condition) {
        if (!ready()) {
            return false;
        }
        ConditionDefinition definition = conditionDefinition("tag", condition.getScope(), condition.getTarget());
        definition.put("tag", TextNode.valueOf(condition.getTag()));
        return scopedStateService.hasTag(actorId(context), definition, targetContext(context));
    }

    public static boolean variable(TriggerContext context, MysticTriggerCondition condition) {
        if (!ready()) {
            return false;
        }
        ConditionDefinition definition = conditionDefinition("variable", condition.getScope(), condition.getTarget());
        definition.put("key", TextNode.valueOf(condition.getKey()));
        definition.put("value", TextNode.valueOf(condition.getValue()));
        definition.put("operator", TextNode.valueOf(condition.getOperator()));
        return scopedStateService.compare(actorId(context), definition, targetContext(context));
    }

    public static void applyTag(TriggerContext context, MysticTriggerEffect effect, boolean add) {
        if (!ready()) {
            return;
        }
        EventDefinition definition = eventDefinition(add ? "addTag" : "removeTag", effect);
        definition.put("tag", TextNode.valueOf(effect.getTag()));
        if (add) {
            scopedStateService.addTag(actorId(context), definition, targetContext(context));
        } else {
            scopedStateService.removeTag(actorId(context), definition, targetContext(context));
        }
    }

    public static void applyVariable(TriggerContext context, MysticTriggerEffect effect, VariableAction action) {
        if (!ready()) {
            return;
        }
        EventDefinition definition = eventDefinition(action.eventType(), effect);
        definition.put("key", TextNode.valueOf(effect.getKey()));
        definition.put("value", TextNode.valueOf(effect.getValue()));
        definition.put("amount", LongNode.valueOf(effect.getAmount()));
        switch (action) {
            case SET -> scopedStateService.setVariable(actorId(context), definition, targetContext(context));
            case REMOVE -> scopedStateService.removeVariable(actorId(context), definition, targetContext(context));
            case INCREMENT -> scopedStateService.incrementVariable(actorId(context), definition, targetContext(context));
        }
    }

    public static void applyEvent(TriggerContext context, MysticTriggerEffect effect) {
        if (questService == null || effect.getEvent() == null || effect.getEvent().isBlank()) {
            return;
        }
        EventDefinition definition = eventDefinition(effect.getEvent(), effect);
        definition.put("tag", TextNode.valueOf(effect.getTag()));
        definition.put("key", TextNode.valueOf(effect.getKey()));
        definition.put("value", TextNode.valueOf(effect.getValue()));
        definition.put("amount", LongNode.valueOf(effect.getAmount()));
        questService.executeEvents(actorId(context), effect.getPackageId(), java.util.List.of(definition), targetContext(context));
    }

    public static QuestTargetContext targetContext(TriggerContext context) {
        VolumeEntry volume = context.getVolume();
        String volumeId = volume == null ? "" : volume.getId();
        String volumeWorld = volume == null ? "" : volume.getWorldName();
        String volumeKey = volumeWorld == null || volumeWorld.isBlank() ? volumeId : volumeWorld + ":" + volumeId;
        Ref<EntityStore> entityRef = context.getEntityRef();
        Entity entity = entity(entityRef, context.getStore());
        String entityId = entity == null || entity.getUuid() == null ? actorId(context).toString() : entity.getUuid().toString();
        String blockId = blockId(context, volumeWorld);
        return new QuestTargetContext(
                entityId,
                entity == null ? "" : entity.getClass().getSimpleName(),
                entity == null ? "" : entity.getLegacyDisplayName(),
                blockId,
                context.getBlockId(),
                volumeWorld,
                volumeId,
                volumeKey,
                volumeWorld);
    }

    private static EventDefinition eventDefinition(String type, MysticTriggerEffect effect) {
        EventDefinition definition = new EventDefinition();
        definition.setType(type);
        putScope(definition, effect.getScope(), effect.getTarget());
        return definition;
    }

    private static ConditionDefinition conditionDefinition(String type, String scope, String target) {
        ConditionDefinition definition = new ConditionDefinition();
        definition.setType(type);
        putScope(definition, scope, target);
        return definition;
    }

    private static void putScope(org.hyzionstudios.mysticquests.model.TypedConfig definition, String scope, String target) {
        if (scope != null && !scope.isBlank()) {
            definition.put("scope", TextNode.valueOf(scope));
        }
        if (target != null && !target.isBlank()) {
            definition.put("target", TextNode.valueOf(target));
        }
    }

    private static UUID actorId(TriggerContext context) {
        Entity entity = entity(context.getEntityRef(), context.getStore());
        if (entity != null && entity.getUuid() != null) {
            return entity.getUuid();
        }
        try {
            Class<?> adapter = Class.forName("org.hyzionstudios.hyextras.TriggerVolumeApiAdapter");
            Object uuid = adapter.getMethod("getEntityUuid", TriggerContext.class).invoke(null, context);
            if (uuid instanceof UUID value) {
                return value;
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            if (logger != null) {
                logger.at(Level.FINE).withCause(exception).log("Unable to resolve HyExtras trigger actor UUID.");
            }
        }
        return new UUID(0L, 0L);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private static Entity entity(Ref<EntityStore> entityRef, Store<EntityStore> store) {
        if (entityRef == null || store == null || !entityRef.isValid()) {
            return null;
        }
        Player player = store.getComponent(entityRef, Player.getComponentType());
        if (player != null) {
            return player;
        }
        var archetype = store.getArchetype(entityRef);
        for (int index = 0; index < archetype.length(); index++) {
            var componentType = archetype.get(index);
            if (componentType == null || componentType.getTypeClass() == null) {
                continue;
            }
            if (Entity.class.isAssignableFrom(componentType.getTypeClass())) {
                var component = store.getComponent(entityRef, componentType);
                if (component instanceof Entity entity) {
                    return entity;
                }
            }
        }
        return null;
    }

    private static String blockId(TriggerContext context, String worldName) {
        if (context.getBlockPosition() == null) {
            return null;
        }
        return (worldName == null ? "" : worldName) + ":"
                + (int) context.getBlockPosition().x + ":"
                + (int) context.getBlockPosition().y + ":"
                + (int) context.getBlockPosition().z;
    }

    public enum VariableAction {
        SET("setVariable"),
        REMOVE("removeVariable"),
        INCREMENT("incrementVariable");

        private final String eventType;

        VariableAction(String eventType) {
            this.eventType = eventType;
        }

        public String eventType() {
            return eventType;
        }
    }
}
