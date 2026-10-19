package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge.GenerationNpc;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.blocktype.config.BlockType;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.UseBlockEvent;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.modules.entity.component.DisplayNameComponent;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code interactObject}, {@code interactEntity} and {@code interactNpc} objectives from the
 * engine's use events.
 *
 * <p>{@code PlayerInteractEvent} is deprecated and nothing in the engine raises it any more, so
 * listening for it counted nothing. A player using a block or an entity now raises
 * {@link UseBlockEvent.Post} or {@link UseEntityEvent.Post} on the player
 * ({@code UseBlockInteraction#doInteraction}, {@code UseEntityInteraction#firstRun}); the engine's own
 * listener is {@code BlackboardSystems.UseBlockEventSystem}. Both fire only after the use actually
 * ran: a block or entity with no interaction for that input raises nothing.
 *
 * <p>As with kills, each distinct name a target answers to is one signal, and an objective names
 * exactly one target, so one use never counts twice for the same objective.
 *
 * <p><b>Runs on the world thread</b>, inside the interaction's tick; components are read through the
 * command buffer, as {@link StoryEntitySystems.UseFilter} does.
 */
public final class InteractObjectiveSystems {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final Query<EntityStore> PLAYERS = Query.and(Player.getComponentType(), PlayerRef.getComponentType());

    private InteractObjectiveSystems() {
    }

    /**
     * A block was used. It answers to its base block id ({@code Furniture_Village_Chest_Small}) and,
     * when it was in an interaction state such as open, to that state's own id too: the engine's NPC
     * block events account for states the same way ({@code BlockEventView#processBlockEvent}).
     */
    public static final class UseBlock extends EntityEventSystem<EntityStore, UseBlockEvent.Post> {
        private final Supplier<QuestSignalBus> signals;

        public UseBlock(Supplier<QuestSignalBus> signals) {
            super(UseBlockEvent.Post.class);
            this.signals = signals;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PLAYERS;
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull UseBlockEvent.Post event) {
            QuestSignalBus bus = signals.get();
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            if (bus == null || player == null) {
                return;
            }
            try {
                BlockType blockType = event.getBlockType();
                String base = Objects.requireNonNullElse(blockType.getDefaultStateKey(), blockType.getId());
                Set<String> names = new LinkedHashSet<>();
                names.add(base);
                names.add(blockType.getId());
                QuestTargetContext context = blockContext(player, event.getTargetBlock(), base);
                for (String name : names) {
                    bus.publish(QuestSignal.targeted(player.getUuid(), "interactObject", name, 1, context));
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not record a block use for quest objectives.");
            }
        }

        /** {@code blockId} is {@code <world uuid>:x:y:z}, so content can also tell one placed block from another. */
        private static QuestTargetContext blockContext(PlayerRef player, Vector3i at, String blockType) {
            String world = player.getWorldUuid() == null ? "" : player.getWorldUuid().toString();
            return new QuestTargetContext(null, null, null, world + ":" + at.x + ":" + at.y + ":" + at.z, blockType,
                    world, null, null, null);
        }
    }

    /**
     * An entity was used. {@code interactEntity} matches its display name, NPC role
     * ({@code Kweebec_Elder}) or model; {@code interactNpc} matches a MysticGeneration definition.
     */
    public static final class UseEntity extends EntityEventSystem<EntityStore, UseEntityEvent.Post> {
        private final Supplier<QuestSignalBus> signals;
        private final Supplier<MysticGenerationBridge> generation;

        public UseEntity(Supplier<QuestSignalBus> signals, Supplier<MysticGenerationBridge> generation) {
            super(UseEntityEvent.Post.class);
            this.signals = signals;
            this.generation = generation;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return PLAYERS;
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull UseEntityEvent.Post event) {
            QuestSignalBus bus = signals.get();
            PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
            Ref<EntityStore> target = event.getTargetEntity();
            if (bus == null || player == null || !target.isValid()) {
                return;
            }
            try {
                publish(bus, player, target, commandBuffer);
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not record an entity use for quest objectives.");
            }
        }

        private void publish(QuestSignalBus bus, PlayerRef player, Ref<EntityStore> target, CommandBuffer<EntityStore> buffer) {
            String displayName = displayName(buffer, target);
            String role = roleName(buffer, target);
            String model = modelAssetId(buffer, target);
            UUIDComponent uuid = buffer.getComponent(target, UUIDComponent.getComponentType());
            String id = uuid == null || uuid.getUuid() == null ? null : uuid.getUuid().toString();

            Set<String> names = new LinkedHashSet<>();
            for (String name : new String[] {displayName, role, model}) {
                if (name != null) {
                    names.add(name);
                }
            }
            if (names.isEmpty() && id != null) {
                names.add(id);
            }

            MysticGenerationBridge bridge = generation.get();
            Optional<GenerationNpc> npc = bridge == null ? Optional.empty() : bridge.identify(buffer, target);
            String world = player.getWorldUuid() == null ? "" : player.getWorldUuid().toString();
            QuestTargetContext context = new QuestTargetContext(id, role != null ? role : model, displayName,
                    null, null, world, null, null, null);
            if (npc.isPresent()) {
                context = context.withGeneration(npc.get().definitionId(), npc.get().uuid().toString());
            }
            for (String name : names) {
                bus.publish(QuestSignal.targeted(player.getUuid(), "interactEntity", name, 1, context));
            }
            // Separate from interactEntity, as before: content keyed on display names keeps working.
            if (npc.isPresent()) {
                bus.publish(QuestSignal.targeted(player.getUuid(), "interactNpc", npc.get().definitionId(), 1, context));
            }
        }

        private static String displayName(CommandBuffer<EntityStore> buffer, Ref<EntityStore> target) {
            DisplayNameComponent component = buffer.getComponent(target, DisplayNameComponent.getComponentType());
            Message name = component == null ? null : component.getDisplayName();
            String text = name == null ? null : name.getAnsiMessage();
            return text == null || text.isBlank() ? null : text;
        }

        private static String roleName(CommandBuffer<EntityStore> buffer, Ref<EntityStore> target) {
            try {
                ComponentType<EntityStore, NPCEntity> type = NPCEntity.getComponentType();
                NPCEntity npc = type == null ? null : buffer.getComponent(target, type);
                String role = npc == null ? null : npc.getRoleName();
                return role == null || role.isBlank() ? null : role;
            } catch (RuntimeException | LinkageError unavailable) {
                return null;
            }
        }

        private static String modelAssetId(CommandBuffer<EntityStore> buffer, Ref<EntityStore> target) {
            ModelComponent model = buffer.getComponent(target, ModelComponent.getComponentType());
            String id = model == null || model.getModel() == null ? null : model.getModel().getModelAssetId();
            return id == null || id.isBlank() ? null : id;
        }
    }
}
