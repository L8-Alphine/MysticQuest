package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.event.events.ecs.UseEntityEvent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageEventSystem;
import com.hypixel.hytale.server.core.modules.entity.damage.DamageModule;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Enforces story entity isolation (§8 of the 2.0 specification): a claimed entity and a player
 * outside its audience cannot target, damage or use each other. Visibility goes through
 * {@code VisibilityService}'s presentation layer, so the existing entity and nameplate systems hide
 * story entities per viewer.
 *
 * <p>Each system is a no-op while nothing is claimed: one volatile read and one empty-map check.
 * The damage and use vetoes follow the engine's own rule systems
 * ({@code TriggerVolumeRuleSystems.DamageRuleFilter} and {@code NoUseEntity}). Damage is filtered in
 * {@code DamageModule#getFilterDamageGroup}, before armour and death, and projectiles are attributed
 * to their shooter by {@code Damage.ProjectileSource}.
 */
public final class StoryEntitySystems {
    private StoryEntitySystems() {
    }

    @Nullable
    private static UUID playerUuid(ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        PlayerRef player = accessor.getComponent(ref, PlayerRef.getComponentType());
        return player == null ? null : player.getUuid();
    }

    @Nullable
    private static UUID entityUuid(ComponentAccessor<EntityStore> accessor, @Nullable Ref<EntityStore> ref) {
        if (ref == null || !ref.isValid()) {
            return null;
        }
        UUIDComponent uuid = accessor.getComponent(ref, UUIDComponent.getComponentType());
        return uuid == null ? null : uuid.getUuid();
    }

    @Nullable
    private static StoryEntityRegistry active(Supplier<StoryEntityRegistry> supplier) {
        StoryEntityRegistry registry = supplier.get();
        return registry == null || registry.isEmpty() ? null : registry;
    }

    /**
     * Whether a hit between these two may land: blocked when one side is a story entity and the
     * other a player outside its audience. Story entities and ordinary NPCs are not restricted
     * against each other.
     */
    static boolean blocked(StoryEntityRegistry registry, @Nullable UUID attackerPlayer, @Nullable UUID attackerEntity,
                           @Nullable UUID victimPlayer, @Nullable UUID victimEntity) {
        if (victimPlayer != null && attackerEntity != null && attackerPlayer == null) {
            return !registry.allows(victimPlayer, attackerEntity);
        }
        if (attackerPlayer != null && victimEntity != null && victimPlayer == null) {
            return !registry.allows(attackerPlayer, victimEntity);
        }
        return false;
    }

    /** Cancels damage between a story entity and a player outside its audience, either way round. */
    public static final class DamageFilter extends DamageEventSystem {
        private final Supplier<StoryEntityRegistry> registry;

        public DamageFilter(Supplier<StoryEntityRegistry> registry) {
            this.registry = registry;
        }

        @Nullable
        @Override
        public SystemGroup<EntityStore> getGroup() {
            return DamageModule.get().getFilterDamageGroup();
        }

        @Override
        public Query<EntityStore> getQuery() {
            return UUIDComponent.getComponentType();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, Damage damage) {
            StoryEntityRegistry story = active(registry);
            if (story == null || !(damage.getSource() instanceof Damage.EntitySource source)) {
                return;
            }
            Ref<EntityStore> victim = chunk.getReferenceTo(index);
            Ref<EntityStore> attacker = source.getRef();
            if (blocked(story, playerUuid(commandBuffer, attacker), entityUuid(commandBuffer, attacker),
                    playerUuid(commandBuffer, victim), entityUuid(commandBuffer, victim))) {
                damage.setCancelled(true);
            }
        }
    }

    /** Cancels a player using (talking to, mounting, picking up) another audience's story entity. */
    public static final class UseFilter extends EntityEventSystem<EntityStore, UseEntityEvent.Pre> {
        private final Supplier<StoryEntityRegistry> registry;

        public UseFilter(Supplier<StoryEntityRegistry> registry) {
            super(UseEntityEvent.Pre.class);
            this.registry = registry;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return Archetype.empty();
        }

        @Override
        public void handle(int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                           CommandBuffer<EntityStore> commandBuffer, UseEntityEvent.Pre event) {
            StoryEntityRegistry story = active(registry);
            if (story == null) {
                return;
            }
            UUID player = playerUuid(commandBuffer, chunk.getReferenceTo(index));
            UUID target = entityUuid(commandBuffer, event.getTargetEntity());
            if (player != null && target != null && !story.allows(player, target)) {
                event.setCancelled(true);
            }
        }
    }

    /**
     * Keeps a story entity's target memory free of players outside its audience, so a story boss
     * never aggroes on someone who cannot see it. Mirrors {@code TargetingPreventionService}'s
     * clearing, but per entity rather than per player.
     */
    public static final class Targeting extends EntityTickingSystem<EntityStore> {
        private final Supplier<StoryEntityRegistry> registry;
        private final Query<EntityStore> query =
                Query.and(TargetMemory.getComponentType(), UUIDComponent.getComponentType());

        public Targeting(Supplier<StoryEntityRegistry> registry) {
            this.registry = registry;
        }

        @Override
        public Query<EntityStore> getQuery() {
            return query;
        }

        @Override
        public void tick(float delta, int index, ArchetypeChunk<EntityStore> chunk, Store<EntityStore> store,
                         CommandBuffer<EntityStore> commandBuffer) {
            StoryEntityRegistry story = active(registry);
            if (story == null) {
                return;
            }
            UUIDComponent self = chunk.getComponent(index, UUIDComponent.getComponentType());
            StoryEntityRegistry.Claim claim = self == null ? null : story.claimOf(self.getUuid());
            if (claim == null) {
                return;
            }
            TargetMemory memory = chunk.getComponent(index, TargetMemory.getComponentType());
            if (memory == null) {
                return;
            }
            List<Ref<EntityStore>> hostiles = memory.getKnownHostilesList();
            if (hostiles != null) {
                hostiles.removeIf(ref -> {
                    UUID player = playerUuid(commandBuffer, ref);
                    if (player != null && !story.allows(player, claim)) {
                        memory.getKnownHostiles().remove(ref.getIndex());
                        return true;
                    }
                    return false;
                });
            }
            UUID closest = playerUuid(commandBuffer, memory.getClosestHostile());
            if (closest != null && !story.allows(closest, claim)) {
                memory.setClosestHostile(null);
            }
        }
    }
}
