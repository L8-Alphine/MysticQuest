package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge.GenerationNpc;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;

import javax.annotation.Nonnull;
import java.util.LinkedHashSet;
import java.util.Optional;
import java.util.Set;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code kill} quest objectives: when a player lands the killing blow on a non-player entity,
 * publishes one {@code kill} signal per name the victim is known by.
 *
 * <p>The killer is read the way the engine's own kill feed and MysticRPG's experience award read it:
 * the death's damage source. A projectile's source reports its shooter, so bow and thrown kills
 * count. Kills by the environment, other mobs or fall damage do not.
 *
 * <p>A victim is matched by, in order of preference for authors:
 * <ul>
 *   <li>its NPC role name, e.g. {@code Wolf_Grey} (the name {@code /npc role} shows and trigger
 *       volume NPC filters use);</li>
 *   <li>its MysticGeneration definition, e.g. {@code hyzion:avalon_guard};</li>
 *   <li>its model asset id, for entities with no role.</li>
 * </ul>
 * Each distinct name is one signal, and an objective names exactly one target, so a kill can never
 * count twice for the same objective.
 *
 * <p><b>Runs on the world thread</b>, as every death system does.
 */
public final class KillObjectiveSystem extends DeathSystems.OnDeathSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Query<EntityStore> VICTIMS = Query.and(
            TransformComponent.getComponentType(),
            Query.not(Player.getComponentType()));

    private final Supplier<QuestSignalBus> signals;
    private final Supplier<MysticGenerationBridge> generation;

    public KillObjectiveSystem(Supplier<QuestSignalBus> signals, Supplier<MysticGenerationBridge> generation) {
        this.signals = signals;
        this.generation = generation;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return VICTIMS;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> victim, @Nonnull DeathComponent death,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        try {
            publish(victim, death, store);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not record a kill for quest objectives.");
        }
    }

    private void publish(Ref<EntityStore> victim, DeathComponent death, Store<EntityStore> store) {
        QuestSignalBus bus = signals.get();
        Damage damage = death.getDeathInfo();
        if (bus == null || damage == null || !(damage.getSource() instanceof Damage.EntitySource source)) {
            return;
        }
        Ref<EntityStore> killer = source.getRef();
        if (killer == null || !killer.isValid()) {
            return;
        }
        PlayerRef player = store.getComponent(killer, PlayerRef.getComponentType());
        if (player == null) {
            return;
        }

        Set<String> names = new LinkedHashSet<>();
        String role = roleName(store, victim);
        if (role != null) {
            names.add(role);
        }
        MysticGenerationBridge bridge = generation.get();
        Optional<GenerationNpc> npc = bridge == null ? Optional.empty() : bridge.identify(store, victim);
        npc.ifPresent(found -> names.add(found.definitionId()));
        String model = modelAssetId(store, victim);
        if (model != null) {
            names.add(model);
        }
        if (names.isEmpty()) {
            return;
        }

        UUIDComponent uuid = store.getComponent(victim, UUIDComponent.getComponentType());
        String world = store.getExternalData().getWorld().getName();
        QuestTargetContext context = new QuestTargetContext(
                uuid == null || uuid.getUuid() == null ? null : uuid.getUuid().toString(),
                role != null ? role : model,
                role,
                null, null, world, null, null, null);
        if (npc.isPresent()) {
            context = context.withGeneration(npc.get().definitionId(), npc.get().uuid().toString());
        }
        for (String name : names) {
            bus.publish(QuestSignal.targeted(player.getUuid(), "kill", name, 1, context));
        }
    }

    private static String roleName(Store<EntityStore> store, Ref<EntityStore> victim) {
        try {
            ComponentType<EntityStore, NPCEntity> type = NPCEntity.getComponentType();
            NPCEntity npc = type == null ? null : store.getComponent(victim, type);
            String role = npc == null ? null : npc.getRoleName();
            return role == null || role.isBlank() ? null : role;
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    private static String modelAssetId(Store<EntityStore> store, Ref<EntityStore> victim) {
        ModelComponent model = store.getComponent(victim, ModelComponent.getComponentType());
        String id = model == null || model.getModel() == null ? null : model.getModel().getModelAssetId();
        return id == null || id.isBlank() ? null : id;
    }
}
