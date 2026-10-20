package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker;
import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker.PlayerPosition;
import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker.Site;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.hypixel.hytale.builtin.adventure.worldevents.component.EventEndEcsEvent;
import com.hypixel.hytale.builtin.adventure.worldevents.component.WorldEventSignal;
import com.hypixel.hytale.builtin.adventure.worldevents.context.LocationContext;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEvent;
import com.hypixel.hytale.builtin.encountermanager.EncounterMembers;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Resource;
import com.hypixel.hytale.component.ResourceType;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.component.system.RefSystem;
import com.hypixel.hytale.component.system.tick.TickingSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;
import org.joml.Vector3i;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code worldEvent} and {@code worldEventSignal} objectives from Hytale's world-event plugin —
 * the system behind the goblin breaches.
 *
 * <p>Four systems, each hooked where the engine itself reports the moment:
 * <ul>
 *   <li>{@link Track} — an event entity is added or removed ({@code EventStartAction#startEvent} adds one
 *       per started event; {@code WorldEventStorageSystem} re-adds them when a world loads).</li>
 *   <li>{@link Observe} — once a second, where each event placed its site (the {@code LocationContext} its
 *       {@code LocationCondition} stored) and which players are near it.</li>
 *   <li>{@link End} — {@link EventEndEcsEvent}, which {@code WorldEventTickSystem} invokes on the event
 *       entity when it succeeds, fails or ends an iteration. Invoked straight away, before the entity's
 *       queued removal, so {@link Track} never forgets an event before its end is heard.</li>
 *   <li>{@link Signal} — {@link WorldEventSignal}, what an encounter's {@code SignalWorldEvent} action
 *       sends: the pre-temple breach pack sends {@code goblin_initial_breach_cleared} once it is beaten.</li>
 * </ul>
 *
 * <p><b>Runs on the world thread.</b> All four are registered once and run in every world's store.
 */
public final class WorldEventSystems {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Blocks around the entity that sent a signal within which players are credited with it. */
    static final double SIGNAL_RADIUS = 64;

    private static final Query<EntityStore> PLAYERS = Query.and(
            Player.getComponentType(), PlayerRef.getComponentType(), TransformComponent.getComponentType());

    private WorldEventSystems() {
    }

    /** Remembers which events run in which world, for {@code worldEventActive} conditions. */
    public static final class Track extends RefSystem<EntityStore> {
        private final Supplier<WorldEventTracker> tracker;

        public Track(Supplier<WorldEventTracker> tracker) {
            this.tracker = tracker;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return WorldEvent.getComponentType();
        }

        @Override
        public void onEntityAdded(@Nonnull Ref<EntityStore> ref, @Nonnull AddReason reason,
                                  @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            WorldEventTracker events = tracker.get();
            WorldEvent event = store.getComponent(ref, WorldEvent.getComponentType());
            if (events != null && event != null && !event.uuid.equals(WorldEvent.EMPTY_ID)) {
                events.started(worldName(store), event.uuid, event.asset);
            }
        }

        @Override
        public void onEntityRemove(@Nonnull Ref<EntityStore> ref, @Nonnull RemoveReason reason,
                                   @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
            WorldEventTracker events = tracker.get();
            WorldEvent event = store.getComponent(ref, WorldEvent.getComponentType());
            // A stale copy replaced on world load has its id blanked first; its replacement keeps the record.
            if (events != null && event != null && !event.uuid.equals(WorldEvent.EMPTY_ID)) {
                events.removed(worldName(store), event.uuid);
            }
        }
    }

    /**
     * Looks at every running event once a second: where it placed its site, and who is near it.
     * One store-wide pass, so the players are read once per world rather than once per event.
     */
    public static final class Observe extends TickingSystem<EntityStore> {
        private static final float INTERVAL_SECONDS = 1f;

        private final ResourceType<EntityStore, Clock> clock = registerResource(Clock.class, Clock::new);
        private final Supplier<WorldEventTracker> tracker;
        private final Supplier<QuestSignalBus> signals;

        public Observe(Supplier<WorldEventTracker> tracker, Supplier<QuestSignalBus> signals) {
            this.tracker = tracker;
            this.signals = signals;
        }

        @Override
        public void tick(float dt, int systemIndex, @Nonnull Store<EntityStore> store) {
            Clock elapsed = store.getResource(clock);
            elapsed.seconds += dt;
            if (elapsed.seconds < INTERVAL_SECONDS) {
                return;
            }
            elapsed.seconds = 0;
            WorldEventTracker events = tracker.get();
            QuestSignalBus bus = signals.get();
            if (events == null || bus == null) {
                return;
            }
            try {
                String world = worldName(store);
                List<PlayerPosition> players = players(store);
                if (players.isEmpty()) {
                    return;
                }
                // Read first, publish after: a signal can complete a quest, and its actions must not run
                // while the store is still iterating event chunks.
                List<Seen> seen = new ArrayList<>();
                store.forEachChunk(WorldEvent.getComponentType(), (chunk, buffer) -> {
                    for (int i = 0; i < chunk.size(); i++) {
                        WorldEvent event = chunk.getComponent(i, WorldEvent.getComponentType());
                        if (event != null && !event.uuid.equals(WorldEvent.EMPTY_ID)) {
                            seen.add(new Seen(event.uuid, event.asset, site(event)));
                        }
                    }
                });
                WorldEventTracker.Emitter emitter = emitter(bus);
                for (Seen event : seen) {
                    events.observe(world, event.id(), event.asset(), event.site(), players, emitter);
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not observe world events for quest objectives.");
            }
        }

        private record Seen(UUID id, String asset, @Nullable Site site) {
        }

        /** Per-store seconds since the last pass; one system instance serves every world. */
        private static final class Clock implements Resource<EntityStore> {
            private float seconds;

            @Nonnull
            @Override
            public Resource<EntityStore> clone() {
                Clock copy = new Clock();
                copy.seconds = seconds;
                return copy;
            }
        }
    }

    /** An event finished or finished one iteration: credits whoever took part. */
    public static final class End extends EntityEventSystem<EntityStore, EventEndEcsEvent> {
        private final Supplier<WorldEventTracker> tracker;
        private final Supplier<QuestSignalBus> signals;

        public End(Supplier<WorldEventTracker> tracker, Supplier<QuestSignalBus> signals) {
            super(EventEndEcsEvent.class);
            this.tracker = tracker;
            this.signals = signals;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return WorldEvent.getComponentType();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull EventEndEcsEvent event) {
            WorldEventTracker events = tracker.get();
            QuestSignalBus bus = signals.get();
            WorldEvent ended = event.getWorldEvent();
            if (events == null || bus == null || ended.uuid.equals(WorldEvent.EMPTY_ID)) {
                return;
            }
            try {
                String world = worldName(store);
                switch (event.getReason()) {
                    case SUCCESS -> events.ended(world, ended.uuid, ended.asset, false, emitter(bus));
                    case ITERATION -> events.ended(world, ended.uuid, ended.asset, true, emitter(bus));
                    // A failed event is removed next; nobody finished anything, so nothing is credited.
                    case FAIL -> events.removed(world, ended.uuid);
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not record a world event ending for quest objectives.");
            }
        }
    }

    /**
     * A world-event signal was sent. Credited to the players it concerns: the sender itself when a
     * player sent it, the members of the encounter that sent it, and every player near the sender.
     */
    public static final class Signal extends EntityEventSystem<EntityStore, WorldEventSignal> {
        private final Supplier<QuestSignalBus> signals;

        public Signal(Supplier<QuestSignalBus> signals) {
            super(WorldEventSignal.class);
            this.signals = signals;
        }

        @Nonnull
        @Override
        public Query<EntityStore> getQuery() {
            return Query.any();
        }

        @Override
        public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                           @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull WorldEventSignal event) {
            QuestSignalBus bus = signals.get();
            if (bus == null || event.signalId().isBlank()) {
                return;
            }
            try {
                Set<UUID> credited = new LinkedHashSet<>();
                PlayerRef sender = chunk.getComponent(index, PlayerRef.getComponentType());
                if (sender != null) {
                    credited.add(sender.getUuid());
                }
                EncounterMembers members = encounterMembers(chunk, index);
                if (members != null) {
                    credited.addAll(memberIds(commandBuffer, members));
                }
                TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
                if (transform != null && transform.getPosition() != null) {
                    credited.addAll(playersNear(store, transform.getPosition(), SIGNAL_RADIUS));
                } else if (sender == null && members == null) {
                    credited.addAll(playerIds(store));
                }
                QuestTargetContext context = worldContext(worldName(store));
                for (UUID player : credited) {
                    bus.publish(QuestSignal.targeted(player, "worldEventSignal", event.signalId(), 1, context));
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not record a world-event signal for quest objectives.");
            }
        }
    }

    /** The site an event placed its content at: the first location any of its conditions stored. */
    @Nullable
    static Site site(WorldEvent event) {
        Vector3i[] found = new Vector3i[1];
        event.context.forEach(LocationContext.class, found, null, (location, into, unused) -> {
            if (into[0] == null) {
                into[0] = location.location();
            }
        });
        return found[0] == null ? null : new Site(found[0].x, found[0].y, found[0].z);
    }

    static List<PlayerPosition> players(Store<EntityStore> store) {
        List<PlayerPosition> players = new ArrayList<>();
        store.forEachChunk(PLAYERS, (chunk, buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                PlayerRef player = chunk.getComponent(i, PlayerRef.getComponentType());
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (player != null && transform != null && transform.getPosition() != null) {
                    Vector3d at = transform.getPosition();
                    players.add(new PlayerPosition(player.getUuid(), at.x, at.y, at.z));
                }
            }
        });
        return players;
    }

    static List<UUID> playerIds(Store<EntityStore> store) {
        return players(store).stream().map(PlayerPosition::player).toList();
    }

    static List<UUID> playersNear(Store<EntityStore> store, Vector3d at, double radius) {
        double limit = radius * radius;
        List<UUID> near = new ArrayList<>();
        for (PlayerPosition player : players(store)) {
            double dx = player.x() - at.x;
            double dy = player.y() - at.y;
            double dz = player.z() - at.z;
            if (dx * dx + dy * dy + dz * dz <= limit) {
                near.add(player.player());
            }
        }
        return near;
    }

    /** The players an encounter counts as in its fight right now. */
    static List<UUID> memberIds(ComponentAccessor<EntityStore> accessor, EncounterMembers members) {
        List<UUID> ids = new ArrayList<>();
        for (Ref<EntityStore> member : members.getMemberTtl().keySet()) {
            if (member == null || !member.isValid()) {
                continue;
            }
            PlayerRef player = accessor.getComponent(member, PlayerRef.getComponentType());
            if (player != null) {
                ids.add(player.getUuid());
            }
        }
        return ids;
    }

    /** Encounter membership, when the sender is an encounter and the encounter plugin is running. */
    @Nullable
    private static EncounterMembers encounterMembers(ArchetypeChunk<EntityStore> chunk, int index) {
        try {
            return chunk.getComponent(index, EncounterMembers.getComponentType());
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    static WorldEventTracker.Emitter emitter(QuestSignalBus bus) {
        return (player, type, asset, world) ->
                bus.publish(QuestSignal.targeted(player, type, asset, 1, worldContext(world)));
    }

    static QuestTargetContext worldContext(String world) {
        return new QuestTargetContext(null, null, null, null, null, world, null, null, null);
    }

    static String worldName(Store<EntityStore> store) {
        return store.getExternalData().getWorld().getName();
    }
}
