package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import it.unimi.dsi.fastutil.ints.IntOpenHashSet;
import it.unimi.dsi.fastutil.ints.IntSet;
import it.unimi.dsi.fastutil.ints.IntSets;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Makes a player untargetable by NPCs, for cutscenes, safe zones, and stealth quest steps.
 *
 * <p>There is no engine flag for this, so protection is enforced by clearing the player out of every
 * NPC's {@link TargetMemory} each tick. Combat evaluation reads that memory, so an NPC that cannot
 * remember the player cannot pick them.
 *
 * <p>Resolving protected players to entity indexes is the expensive half, and the naive version does
 * it once per NPC per tick. Results are cached per store and reused for the rest of the tick, then
 * invalidated whenever the protected set changes.
 */
public final class TargetingPreventionService {
    /** How long a resolved index set stays usable — about one tick at 20 ticks per second. */
    private static final long CACHE_TTL_NANOS = 50L * 1_000_000L;

    private final PlayerSessionService sessions;
    private final MysticQuestsEventBus eventBus;

    private final Set<UUID> protectedPlayers = ConcurrentHashMap.newKeySet();
    private final Map<Store<EntityStore>, CachedIndexes> indexCache = new ConcurrentHashMap<>();

    /** Bumped on every membership change so cached index sets are recognised as stale. */
    private volatile int generation;

    public TargetingPreventionService(PlayerSessionService sessions, MysticQuestsEventBus eventBus) {
        this.sessions = sessions;
        this.eventBus = eventBus;
    }

    public boolean protectPlayer(UUID player) {
        if (player == null || !protectedPlayers.add(player)) {
            return false;
        }
        generation++;
        eventBus.post(new StateEvents.TargetingChange(player, true));
        return true;
    }

    public boolean unprotectPlayer(UUID player) {
        if (player == null || !protectedPlayers.remove(player)) {
            return false;
        }
        generation++;
        eventBus.post(new StateEvents.TargetingChange(player, false));
        return true;
    }

    public boolean isProtected(UUID player) {
        return player != null && protectedPlayers.contains(player);
    }

    /** True when at least one player is protected. The per-NPC tick path checks this first. */
    public boolean hasProtectedPlayers() {
        return !protectedPlayers.isEmpty();
    }

    public Set<UUID> snapshotProtectedPlayers() {
        return Set.copyOf(protectedPlayers);
    }

    public void clear() {
        for (UUID player : Set.copyOf(protectedPlayers)) {
            unprotectPlayer(player);
        }
        protectedPlayers.clear();
        indexCache.clear();
        generation++;
    }

    /**
     * Entity indexes of the protected players that live in this store.
     *
     * <p>The targeting system asks for this once per NPC and the answer is identical for all of them,
     * so it is cached per store. The cache is dropped as soon as the protected set changes, and
     * otherwise expires after {@link #CACHE_TTL_NANOS} — roughly a tick — so an index that moves
     * because a player respawned or changed world is picked up on the next pass rather than leaving
     * them unprotected.
     */
    public IntSet protectedEntityIndexes(Store<EntityStore> store) {
        if (store == null || protectedPlayers.isEmpty()) {
            return IntSets.EMPTY_SET;
        }
        int currentGeneration = generation;
        long now = System.nanoTime();
        CachedIndexes cached = indexCache.get(store);
        if (cached != null
                && cached.generation == currentGeneration
                && now - cached.computedAtNanos < CACHE_TTL_NANOS) {
            return cached.indexes;
        }

        IntSet indexes = new IntOpenHashSet(protectedPlayers.size());
        for (UUID player : protectedPlayers) {
            PlayerRef playerRef = sessions.playerRef(player);
            if (playerRef == null) {
                continue;
            }
            Ref<EntityStore> reference = playerRef.getReference();
            if (reference != null && reference.isValid() && reference.getStore() == store) {
                indexes.add(reference.getIndex());
            }
        }
        indexCache.put(store, new CachedIndexes(indexes, now, currentGeneration));
        return indexes;
    }

    /**
     * Removes protected players from one NPC's target memory.
     *
     * @return how many references were cleared, for debug logging
     */
    public int clearProtectedTargets(TargetMemory memory, IntSet protectedIndexes) {
        if (memory == null || protectedIndexes.isEmpty()) {
            return 0;
        }
        int cleared = 0;

        List<Ref<EntityStore>> hostiles = memory.getKnownHostilesList();
        if (hostiles != null) {
            int before = hostiles.size();
            hostiles.removeIf(ref -> ref != null && protectedIndexes.contains(ref.getIndex()));
            cleared += before - hostiles.size();
        }

        for (int index : protectedIndexes) {
            if (memory.getKnownHostiles().remove(index) != 0.0F) {
                cleared++;
            }
        }

        Ref<EntityStore> closest = memory.getClosestHostile();
        if (closest != null && protectedIndexes.contains(closest.getIndex())) {
            memory.setClosestHostile(null);
            cleared++;
        }
        return cleared;
    }

    /** Drops cached index sets for stores that are no longer loaded. */
    public void forgetStore(Store<EntityStore> store) {
        indexCache.remove(store);
    }

    private record CachedIndexes(IntSet indexes, long computedAtNanos, int generation) {
    }
}
