package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.entities.NPCEntity;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code encounter} and {@code bossDefeated} objectives from Hytale's encounter managers — the
 * invisible entities that run boss fights such as the Gobliterator and spawn breach packs.
 *
 * <p>An encounter keeps the players it counts as in its fight in {@link EncounterMembers}; it is who
 * {@code ActionGrantReward} pays when the Gobliterator dies ({@code Reward_Cosmetics_Chapter1}). These
 * systems use that same membership:
 * <ul>
 *   <li>{@link Membership} — a player joining an encounter's fight raises {@code encounterJoined} with the
 *       encounter's id, e.g. {@code Goblin_Gobliterator_Encounter}: the boss fight was found.</li>
 *   <li>{@link BossDeath} — an NPC whose role a quest watches dies. Everyone who was in the fight is
 *       credited with {@code bossDefeated}: the members of any encounter near the body, the players
 *       standing near it, and whoever landed the blow. A kill objective credits only the last hit,
 *       which on a group boss is one player of several.</li>
 * </ul>
 *
 * <p><b>Runs on the world thread.</b>
 */
public final class EncounterSystems {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Blocks around a dead boss within which an encounter counts as its fight; the Gobliterator's beacon range is 66. */
    static final double ENCOUNTER_RANGE = 80;
    /** Blocks around a dead boss within which a player counts as having fought it; its arena is 26.8 across. */
    static final double BOSS_RADIUS = 48;

    private EncounterSystems() {
    }

    /** Raises {@code encounterJoined} when a player enters an encounter's fight. */
    public static final class Membership extends DelayedEntitySystem<EntityStore> {
        private static final float INTERVAL_SECONDS = 1f;

        private final Supplier<QuestSignalBus> signals;
        /** The players each live encounter counted last time, so only arrivals are announced. */
        private final Map<Ref<EntityStore>, Set<UUID>> known = new ConcurrentHashMap<>();

        public Membership(Supplier<QuestSignalBus> signals) {
            super(INTERVAL_SECONDS);
            this.signals = signals;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.and(EncounterManager.getComponentType(), EncounterMembers.getComponentType());
        }

        /** Members can be shared by overlapping encounters; one at a time keeps {@link #known} simple. */
        @Override
        public boolean isParallel(int archetypeChunkSize, int taskCount) {
            return false;
        }

        @Override
        public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                         @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            QuestSignalBus bus = signals.get();
            EncounterManager encounter = chunk.getComponent(index, EncounterManager.getComponentType());
            EncounterMembers members = chunk.getComponent(index, EncounterMembers.getComponentType());
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (bus == null || encounter == null || members == null || ref == null || encounter.getEncounterId() == null) {
                return;
            }
            known.keySet().removeIf(entry -> !entry.isValid());
            Set<UUID> current = new LinkedHashSet<>(WorldEventSystems.memberIds(commandBuffer, members));
            Set<UUID> previous = known.put(ref, current);
            if (current.isEmpty()) {
                return;
            }
            QuestTargetContext context = WorldEventSystems.worldContext(WorldEventSystems.worldName(store));
            for (UUID player : current) {
                if (previous == null || !previous.contains(player)) {
                    bus.publish(QuestSignal.targeted(player, "encounterJoined", encounter.getEncounterId(), 1, context));
                }
            }
        }
    }

    /** Raises {@code bossDefeated} for everyone in the fight when a watched NPC dies. */
    public static final class BossDeath extends DeathSystems.OnDeathSystem {
        private static final Query<EntityStore> VICTIMS = Query.and(
                TransformComponent.getComponentType(),
                Query.not(Player.getComponentType()));

        private final Supplier<QuestSignalBus> signals;
        private final Supplier<Predicate<String>> watched;

        /**
         * @param watched which NPC roles some loaded quest names in a {@code bossDefeated} objective or
         *                start trigger; every other death is ignored without a lookup
         */
        public BossDeath(Supplier<QuestSignalBus> signals, Supplier<Predicate<String>> watched) {
            this.signals = signals;
            this.watched = watched;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return VICTIMS;
        }

        @Override
        public void onComponentAdded(@Nonnull Ref<EntityStore> victim, @Nonnull DeathComponent death,
                                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            QuestSignalBus bus = signals.get();
            Predicate<String> roles = watched.get();
            if (bus == null || roles == null) {
                return;
            }
            try {
                String role = roleName(store, victim);
                if (role == null || !roles.test(role)) {
                    return;
                }
                TransformComponent transform = store.getComponent(victim, TransformComponent.getComponentType());
                Vector3d at = transform == null ? null : transform.getPosition();
                Set<UUID> credited = new LinkedHashSet<>();
                UUID killer = killer(store, death);
                if (killer != null) {
                    credited.add(killer);
                }
                if (at != null) {
                    credited.addAll(encounterMembersNear(store, commandBuffer, at));
                    credited.addAll(WorldEventSystems.playersNear(store, at, BOSS_RADIUS));
                }
                UUIDComponent uuid = store.getComponent(victim, UUIDComponent.getComponentType());
                String world = WorldEventSystems.worldName(store);
                QuestTargetContext context = new QuestTargetContext(
                        uuid == null || uuid.getUuid() == null ? null : uuid.getUuid().toString(),
                        role, role, null, null, world, null, null, null);
                for (UUID player : credited) {
                    bus.publish(QuestSignal.targeted(player, "bossDefeated", role, 1, context));
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not record a boss defeat for quest objectives.");
            }
        }

        private static List<UUID> encounterMembersNear(Store<EntityStore> store, CommandBuffer<EntityStore> buffer, Vector3d at) {
            List<UUID> members = new ArrayList<>();
            double limit = ENCOUNTER_RANGE * ENCOUNTER_RANGE;
            store.forEachChunk(Query.and(EncounterMembers.getComponentType(), TransformComponent.getComponentType()),
                    (chunk, ignored) -> {
                        for (int i = 0; i < chunk.size(); i++) {
                            TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                            EncounterMembers encounter = chunk.getComponent(i, EncounterMembers.getComponentType());
                            if (transform == null || encounter == null || transform.getPosition() == null) {
                                continue;
                            }
                            if (transform.getPosition().distanceSquared(at) <= limit) {
                                members.addAll(WorldEventSystems.memberIds(buffer, encounter));
                            }
                        }
                    });
            return members;
        }

        /** The player who landed the killing blow, read as {@link KillObjectiveSystem} reads it. */
        private static UUID killer(Store<EntityStore> store, DeathComponent death) {
            Damage damage = death.getDeathInfo();
            if (damage == null || !(damage.getSource() instanceof Damage.EntitySource source)) {
                return null;
            }
            Ref<EntityStore> ref = source.getRef();
            if (ref == null || !ref.isValid()) {
                return null;
            }
            PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
            return player == null ? null : player.getUuid();
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
    }
}
