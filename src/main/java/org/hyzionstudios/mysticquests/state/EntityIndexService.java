package org.hyzionstudios.mysticquests.state;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Tracks where nearby non-player entities are, so quests and trigger volumes can address "the
 * closest NPC" without walking the entity store themselves.
 *
 * <p>Entity tags and variables live in {@link MysticStateStore} under {@link StateScope#ENTITY};
 * this only answers "which entity is where", and forgets entities that stop being ticked.
 *
 * <p>Two things matter here because {@code index} is called for every tracked entity on every tick:
 * entries are allocated once and then mutated in place rather than rebuilt, and freshness is stamped
 * with {@link System#nanoTime()} rather than an allocated timestamp object. Pruning is rate-limited
 * so it does not run once per entity per tick.
 */
public final class EntityIndexService {
    private static final long DEFAULT_RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long PRUNE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final Map<UUID, IndexedEntity> entities = new ConcurrentHashMap<>();
    private final Map<UUID, String> displayNames = new ConcurrentHashMap<>();
    private final AtomicLong lastPruneNanos = new AtomicLong(System.nanoTime());
    private final long retentionNanos;

    public EntityIndexService() {
        this(DEFAULT_RETENTION_NANOS);
    }

    EntityIndexService(long retentionNanos) {
        this.retentionNanos = retentionNanos;
    }

    /**
     * Records an entity's current position. Called from the entity ticking system, so this is the
     * hottest method in the state package — it must not allocate for an entity already indexed.
     */
    public void index(Store<EntityStore> store, Ref<EntityStore> ref, UUID entity, Vector3d position) {
        if (entity == null || position == null) {
            return;
        }
        long now = System.nanoTime();
        IndexedEntity existing = entities.get(entity);
        if (existing == null) {
            entities.put(entity, new IndexedEntity(store, ref, position.x, position.y, position.z, now));
        } else {
            existing.update(store, ref, position.x, position.y, position.z, now);
        }
        pruneIfDue(now);
    }

    /**
     * Returns the closest indexed entity to {@code origin} within {@code radius} in the same store,
     * or null when nothing qualifies.
     */
    @Nullable
    public UUID findClosest(Store<EntityStore> store, Vector3d origin, double radius) {
        if (store == null || origin == null || radius <= 0.0D) {
            return null;
        }
        double radiusSquared = radius * radius;
        UUID closest = null;
        double closestDistance = Double.MAX_VALUE;
        for (Map.Entry<UUID, IndexedEntity> candidate : entities.entrySet()) {
            IndexedEntity indexed = candidate.getValue();
            if (indexed.store != store || !indexed.isLive()) {
                continue;
            }
            double distance = indexed.distanceSquaredTo(origin);
            if (distance <= radiusSquared && distance < closestDistance) {
                closestDistance = distance;
                closest = candidate.getKey();
            }
        }
        return closest;
    }

    /** The ref for an indexed entity, or null when it is unknown or no longer valid. */
    @Nullable
    public Ref<EntityStore> reference(UUID entity) {
        IndexedEntity indexed = entity == null ? null : entities.get(entity);
        return indexed != null && indexed.isLive() ? indexed.ref : null;
    }

    /** The store an indexed entity lives in, or null when it is unknown or no longer valid. */
    @Nullable
    public Store<EntityStore> store(UUID entity) {
        IndexedEntity indexed = entity == null ? null : entities.get(entity);
        return indexed != null && indexed.isLive() ? indexed.store : null;
    }

    public boolean isIndexed(UUID entity) {
        IndexedEntity indexed = entity == null ? null : entities.get(entity);
        return indexed != null && indexed.isLive();
    }

    public java.util.Set<UUID> indexedEntities() {
        return java.util.Set.copyOf(entities.keySet());
    }

    /** Sets a developer-facing display name for an entity; blank clears it. */
    public void setDisplayName(UUID entity, @Nullable String displayName) {
        if (entity == null) {
            return;
        }
        if (displayName == null || displayName.isBlank()) {
            displayNames.remove(entity);
        } else {
            displayNames.put(entity, displayName.trim());
        }
    }

    @Nullable
    public String displayName(UUID entity) {
        return entity == null ? null : displayNames.get(entity);
    }

    public void forget(UUID entity) {
        if (entity != null) {
            entities.remove(entity);
            displayNames.remove(entity);
        }
    }

    public void clear() {
        entities.clear();
        displayNames.clear();
    }

    /**
     * Drops entities that have gone invalid or stopped being ticked. Rate-limited: only the first
     * caller past the interval does the work, everyone else returns immediately.
     */
    private void pruneIfDue(long now) {
        long last = lastPruneNanos.get();
        if (now - last < PRUNE_INTERVAL_NANOS || !lastPruneNanos.compareAndSet(last, now)) {
            return;
        }
        entities.entrySet().removeIf(entry -> {
            IndexedEntity indexed = entry.getValue();
            boolean stale = !indexed.isLive() || now - indexed.lastSeenNanos > retentionNanos;
            if (stale) {
                displayNames.remove(entry.getKey());
            }
            return stale;
        });
    }

    /**
     * One entity's index entry. Fields are volatile and written in place; a reader that catches a
     * half-updated position sees an entity a fraction of a tick out of date, which is immaterial for
     * proximity queries and much cheaper than allocating a fresh record every tick.
     */
    private static final class IndexedEntity {
        private volatile Store<EntityStore> store;
        private volatile Ref<EntityStore> ref;
        private volatile double x;
        private volatile double y;
        private volatile double z;
        private volatile long lastSeenNanos;

        private IndexedEntity(
                Store<EntityStore> store, Ref<EntityStore> ref, double x, double y, double z, long seenNanos) {
            update(store, ref, x, y, z, seenNanos);
        }

        private void update(
                Store<EntityStore> store, Ref<EntityStore> ref, double x, double y, double z, long seenNanos) {
            this.store = store;
            this.ref = ref;
            this.x = x;
            this.y = y;
            this.z = z;
            this.lastSeenNanos = seenNanos;
        }

        private boolean isLive() {
            Ref<EntityStore> current = ref;
            return current != null && current.isValid();
        }

        private double distanceSquaredTo(Vector3d origin) {
            double dx = x - origin.x;
            double dy = y - origin.y;
            double dz = z - origin.z;
            return dx * dx + dy * dy + dz * dz;
        }
    }
}
