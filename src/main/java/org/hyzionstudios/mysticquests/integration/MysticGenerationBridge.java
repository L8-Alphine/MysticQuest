package org.hyzionstudios.mysticquests.integration;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.RemoveReason;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.math.vector.Rotation3fc;
import com.hypixel.hytale.server.core.plugin.PluginBase;
import com.hypixel.hytale.server.core.plugin.PluginManager;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;
import org.joml.Vector3dc;

import java.lang.reflect.Method;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Optional binding to MysticGeneration, so quests can address NPCs authored in its Studio.
 *
 * <h2>Why identity rather than name</h2>
 *
 * <p>MysticGeneration NPCs are ordinary native Hytale entities, so MysticQuests already sees them —
 * what it cannot do unaided is name one durably. Matching on display name or entity UUID breaks the
 * moment a definition is republished: MysticGeneration's role change is implemented as
 * {@code removeEntity} then {@code addEntity}, which rekeys the entity's {@code Ref} and UUID. Its
 * {@code GenerationIdentity} component carries a stable UUID and the definition id across that, and
 * across chunk unload, so those are what conversations, objectives, and entity-scope state key on.
 *
 * <h2>Optional by construction</h2>
 *
 * <p>MysticGeneration is not a compile-time dependency and its classes may live in a different
 * plugin classloader, so everything here is reflective and every failure degrades to "no
 * MysticGeneration", never to an exception reaching quest content. The plugin instance is found by
 * scanning {@link PluginManager} for a loaded plugin whose class name matches, which works whether
 * or not this jar can see MysticGeneration's classes directly.
 *
 * <p>Resolution is retried while the plugin is absent, because MysticGeneration publishes its
 * instance at the end of its own {@code setup()} and mod start order is not guaranteed.
 */
public final class MysticGenerationBridge implements AutoCloseable {
    private static final String PLUGIN_CLASS = "org.hyzionstudios.mysticgeneration.MysticgenerationPlugin";

    /** Definition states MysticGeneration will spawn from, mirroring {@code RevisionState}. */
    private static final List<String> SPAWNABLE_STATES = List.of("STAGED", "PUBLISHED");

    /** How long an unbound bridge waits before looking for MysticGeneration again. */
    private static final long RETRY_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(30);

    /** How long an indexed NPC stays addressable after it last ticked. Mirrors the entity index. */
    private static final long RETENTION_NANOS = TimeUnit.SECONDS.toNanos(10);
    private static final long PRUNE_INTERVAL_NANOS = TimeUnit.SECONDS.toNanos(1);

    private final boolean enabled;
    private final HytaleLogger logger;
    private final ObjectMapper mapper = new ObjectMapper();

    /** Live generated NPCs by stable identity, fed by the entity tick and read from any thread. */
    private final Map<UUID, IndexedNpc> npcs = new ConcurrentHashMap<>();
    private volatile long lastPruneNanos = System.nanoTime();

    private volatile Object plugin;
    private volatile ComponentType<EntityStore, ?> identityComponentType;
    private volatile Method uuidMethod;
    private volatile Method definitionIdMethod;
    private volatile Method revisionMethod;
    private volatile Path dataDirectory;
    private volatile boolean available;

    /** Set when MysticGeneration answered but could not be bound, which retrying will not fix. */
    private volatile boolean resolutionFailed;

    /**
     * When the last resolution attempt ran.
     *
     * <p>Identity lookups happen per entity during the interactable scan, and an unbound bridge
     * would otherwise walk the plugin list on every one of them. Retrying stays necessary — mod
     * start order is not guaranteed — so it is throttled rather than abandoned.
     */
    private volatile long lastAttemptNanos = Long.MIN_VALUE;

    /** Set by {@link #close()}; the bridge never binds again after MysticQuests has shut down. */
    private volatile boolean closed;

    public MysticGenerationBridge(boolean enabled, HytaleLogger logger) {
        this.enabled = enabled;
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    /**
     * Attempts to bind to a running MysticGeneration.
     *
     * <p>Safe to call when MysticGeneration is absent, half-started, or a build without the
     * integration accessors: each case logs at FINE and leaves the bridge unavailable.
     */
    public void register() {
        if (!enabled || available || resolutionFailed || closed) {
            return;
        }
        lastAttemptNanos = System.nanoTime();
        Object located = locatePlugin();
        if (located == null) {
            logger.at(Level.FINE).log("MysticGeneration is not installed; quests will not bind to generated NPCs.");
            return;
        }
        try {
            Object identityService = located.getClass().getMethod("identity").invoke(located);
            if (identityService == null) {
                // Reachable only if another mod resolves us mid-setup; a later call succeeds.
                logger.at(Level.FINE).log("MysticGeneration is present but has not finished setup.");
                return;
            }
            Object componentType = identityService.getClass().getMethod("componentType").invoke(identityService);
            if (!(componentType instanceof ComponentType<?, ?> type)) {
                logger.at(Level.WARNING).log("MysticGeneration exposed an unexpected identity component type.");
                resolutionFailed = true;
                return;
            }
            @SuppressWarnings("unchecked")
            ComponentType<EntityStore, ?> entityType = (ComponentType<EntityStore, ?>) type;
            Class<?> identityClass = type.getTypeClass();
            this.identityComponentType = entityType;
            this.uuidMethod = identityClass.getMethod("uuid");
            this.definitionIdMethod = identityClass.getMethod("definitionId");
            this.revisionMethod = identityClass.getMethod("revision");
            this.dataDirectory = resolveDataDirectory(located);
            this.plugin = located;
            this.available = true;
            logger.at(Level.INFO).log("MysticGeneration bridge enabled; quests can bind to generated NPCs.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            resolutionFailed = true;
            logger.at(Level.WARNING).withCause(exception).log("Failed to enable the optional MysticGeneration bridge.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean available() {
        return available;
    }

    /**
     * The identity of one live entity, or empty when it is not a MysticGeneration NPC.
     *
     * <p>Called on the interaction path, so a bridge that never bound costs one volatile read.
     */
    public Optional<GenerationNpc> identify(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        if (!ready() || accessor == null || ref == null || !ref.isValid()) {
            return Optional.empty();
        }
        try {
            return read(accessor.getComponent(ref, identityComponentType));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Chunk-local overload, for the interactable reconcile scan that already walks every chunk. */
    public Optional<GenerationNpc> identify(ArchetypeChunk<EntityStore> chunk, int index) {
        if (!ready() || chunk == null) {
            return Optional.empty();
        }
        try {
            return read(chunk.getComponent(index, identityComponentType));
        } catch (RuntimeException exception) {
            return Optional.empty();
        }
    }

    /**
     * Records a live generated NPC. Called once per entity per tick from {@code EntityIndexSystem},
     * which is the only place that is guaranteed to be on the store thread.
     *
     * <p>The index exists so lookups do not walk the entity store: a quest action can run from a
     * scheduler or a packet thread, and iterating ECS from there races the tick that owns it.
     */
    public void index(Store<EntityStore> store, Ref<EntityStore> ref, ArchetypeChunk<EntityStore> chunk, int slot) {
        if (!ready() || store == null || ref == null) {
            return;
        }
        Optional<GenerationNpc> npc = identify(chunk, slot);
        if (npc.isEmpty()) {
            return;
        }
        long now = System.nanoTime();
        npcs.put(npc.get().uuid(), new IndexedNpc(npc.get().definitionId(), store, ref, now));
        pruneIfDue(now);
    }

    /**
     * The stable identities of every live NPC in {@code store} spawned from {@code definitionId}.
     *
     * <p>Backs {@code generation:<definition>} target selectors, so content can address "every
     * guard" without listing UUIDs that change on every republish. Reads the tick-fed index, so it
     * is safe from any thread and only sees NPCs that have ticked recently — a dormant NPC that
     * MysticGeneration has moved off-screen is deliberately not a target.
     */
    public List<UUID> entitiesOf(Store<EntityStore> store, String definitionId) {
        if (!available || store == null || definitionId == null || definitionId.isBlank()) {
            return List.of();
        }
        List<UUID> matches = new ArrayList<>();
        for (Map.Entry<UUID, IndexedNpc> candidate : npcs.entrySet()) {
            IndexedNpc indexed = candidate.getValue();
            if (indexed.store == store
                    && definitionId.equalsIgnoreCase(indexed.definitionId)
                    && indexed.isLive()) {
                matches.add(candidate.getKey());
            }
        }
        return matches;
    }

    /** The live entity carrying {@code stableUuid}, or null when it is not currently spawned. */
    public Ref<EntityStore> reference(Store<EntityStore> store, UUID stableUuid) {
        if (!available || store == null || stableUuid == null) {
            return null;
        }
        IndexedNpc indexed = npcs.get(stableUuid);
        return indexed == null || indexed.store != store || !indexed.isLive() ? null : indexed.ref;
    }

    /**
     * Spawns a definition by name and reports the stable UUID of the result.
     *
     * <p>The spawn itself is MysticGeneration's, through the only path it supports, so identity is
     * attached before the entity enters ECS. Definition metadata is read from its data directory
     * because the native role name and revision are properties of the authored definition, not
     * something quest content should have to repeat.
     *
     * <p>{@code onSpawned} runs on the world's store thread, which is also the only thread the spawn
     * can happen on — hence a callback rather than a return value: a call made off-thread is
     * scheduled, not refused.
     */
    public void spawn(
            Store<EntityStore> store,
            String definitionName,
            Vector3dc position,
            float yaw,
            Consumer<GenerationNpc> onSpawned) {
        if (!ready() || store == null || position == null) {
            return;
        }
        DefinitionMetadata definition = readDefinition(definitionName);
        if (definition == null) {
            return;
        }
        Vector3d spawnAt = new Vector3d(position);
        Rotation3fc rotation = new Rotation3f(0.0F, yaw, 0.0F);
        onStoreThread(store, () -> {
            try {
                Object spawnService = plugin.getClass().getMethod("spawns").invoke(plugin);
                Method spawn = spawnService.getClass().getMethod(
                        "spawn", Store.class, String.class, String.class, int.class,
                        Vector3dc.class, Rotation3fc.class);
                Object spawned = spawn.invoke(
                        spawnService, store, definition.roleName(), definition.id(),
                        definition.revision(), spawnAt, rotation);
                if (spawned == null) {
                    return;
                }
                UUID uuid = (UUID) spawned.getClass().getMethod("uuid").invoke(spawned);
                GenerationNpc npc = new GenerationNpc(uuid, definition.id(), definition.revision());
                if (onSpawned != null) {
                    onSpawned.accept(npc);
                }
            } catch (ReflectiveOperationException | RuntimeException exception) {
                // An uncompiled definition lands here as an IllegalArgumentException from
                // MysticGeneration. That is content's problem to fix, not a bridge fault.
                logger.at(Level.WARNING).withCause(exception)
                        .log("Failed to spawn MysticGeneration definition '" + definitionName + "'.");
            }
        });
    }

    /**
     * Removes a live generated NPC and forgets any off-screen record for it.
     *
     * <p>Purging the record matters: MysticGeneration restores dematerialized entities when a player
     * comes back into range, so removing only the live entity would let a quest-despawned NPC
     * reappear later.
     */
    public void despawn(Store<EntityStore> store, UUID stableUuid) {
        if (!ready() || store == null || stableUuid == null) {
            return;
        }
        onStoreThread(store, () -> {
            Ref<EntityStore> ref = reference(store, stableUuid);
            if (ref != null && ref.isValid()) {
                try {
                    store.removeEntity(ref, RemoveReason.REMOVE);
                } catch (RuntimeException exception) {
                    logger.at(Level.FINE).withCause(exception)
                            .log("Failed to remove MysticGeneration entity " + stableUuid + ".");
                }
            }
            forgetOffscreen(stableUuid);
        });
    }

    @Override
    public void close() {
        // Also stops the retry path, so a late call during shutdown cannot re-bind a torn-down mod.
        closed = true;
        available = false;
        plugin = null;
        identityComponentType = null;
        uuidMethod = null;
        definitionIdMethod = null;
        revisionMethod = null;
        dataDirectory = null;
    }

    /**
     * True when the bridge can be used right now, retrying resolution at most once per
     * {@link #RETRY_INTERVAL_NANOS} while MysticGeneration has neither bound nor been ruled out.
     */
    private boolean ready() {
        if (available) {
            return true;
        }
        if (!enabled || resolutionFailed || closed) {
            return false;
        }
        if (System.nanoTime() - lastAttemptNanos < RETRY_INTERVAL_NANOS) {
            return false;
        }
        register();
        return available;
    }

    private Object locatePlugin() {
        try {
            PluginManager manager = PluginManager.get();
            if (manager == null) {
                return null;
            }
            for (PluginBase loaded : manager.getPlugins()) {
                if (loaded != null && PLUGIN_CLASS.equals(loaded.getClass().getName())) {
                    return loaded;
                }
            }
        } catch (RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Could not query the plugin manager for MysticGeneration.");
        }
        return null;
    }

    /**
     * MysticGeneration's stable runtime directory. Older builds without the accessor fall back to
     * the documented {@code mods/MysticGeneration} layout.
     */
    private Path resolveDataDirectory(Object located) {
        try {
            Object directory = located.getClass().getMethod("dataDirectory").invoke(located);
            if (directory instanceof Path path) {
                return path;
            }
        } catch (ReflectiveOperationException | RuntimeException ignored) {
            // Falls through to the documented default below.
        }
        return PluginManager.MODS_PATH.resolve("MysticGeneration");
    }

    private Optional<GenerationNpc> read(Object identity) {
        if (identity == null) {
            return Optional.empty();
        }
        try {
            Object uuid = uuidMethod.invoke(identity);
            Object definitionId = definitionIdMethod.invoke(identity);
            if (!(uuid instanceof UUID stable) || definitionId == null || definitionId.toString().isBlank()) {
                return Optional.empty();
            }
            Object revision = revisionMethod.invoke(identity);
            return Optional.of(new GenerationNpc(
                    stable,
                    definitionId.toString(),
                    revision instanceof Integer number ? number : 0));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return Optional.empty();
        }
    }

    /** Drops entities that have stopped ticking, so the index cannot grow without bound. */
    private void pruneIfDue(long now) {
        long previous = lastPruneNanos;
        if (now - previous < PRUNE_INTERVAL_NANOS) {
            return;
        }
        lastPruneNanos = now;
        npcs.values().removeIf(indexed -> !indexed.isLive());
    }

    private void forgetOffscreen(UUID stableUuid) {
        try {
            Object repository = plugin.getClass().getMethod("offscreenEntities").invoke(plugin);
            if (repository == null) {
                return;
            }
            Object found = repository.getClass().getMethod("find", UUID.class).invoke(repository, stableUuid);
            if (!(found instanceof Optional<?> record) || record.isEmpty()) {
                return;
            }
            for (Method method : repository.getClass().getMethods()) {
                if ("delete".equals(method.getName()) && method.getParameterCount() == 2) {
                    method.invoke(repository, stableUuid, record.get());
                    return;
                }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception)
                    .log("Failed to clear the off-screen record for " + stableUuid + ".");
        }
    }

    /**
     * Definition metadata as authored on disk.
     *
     * <p>The native role name is derived rather than compiled: MysticGeneration defines it as a pure
     * function of the definition id ({@code hyzion:guard} becomes {@code hyzion_guard}), so reading
     * the JSON is enough and no compiler needs to run here.
     */
    private DefinitionMetadata readDefinition(String definitionName) {
        Path directory = dataDirectory;
        if (directory == null || definitionName == null || definitionName.isBlank()) {
            return null;
        }
        String name = definitionName.trim();
        if (name.contains("/") || name.contains("\\") || name.contains("..")) {
            logger.at(Level.WARNING).log("Refusing MysticGeneration definition name with a path in it: " + name);
            return null;
        }
        Path source = directory.resolve("definitions").resolve(name.endsWith(".json") ? name : name + ".json");
        if (!Files.isRegularFile(source)) {
            logger.at(Level.WARNING).log("No MysticGeneration definition at " + source + ".");
            return null;
        }
        try {
            JsonNode root = mapper.readTree(Files.readString(source, StandardCharsets.UTF_8));
            String id = root.path("id").asText("");
            if (id.isBlank()) {
                logger.at(Level.WARNING).log("MysticGeneration definition " + name + " has no id.");
                return null;
            }
            String state = root.path("state").asText("").toUpperCase(Locale.ROOT);
            if (!state.isEmpty() && !SPAWNABLE_STATES.contains(state)) {
                logger.at(Level.WARNING).log(
                        "MysticGeneration definition " + name + " is " + state + " and cannot be spawned.");
                return null;
            }
            int revision = Math.max(1, root.path("revision").asInt(1));
            return new DefinitionMetadata(id, toNativeRoleName(id), revision);
        } catch (Exception exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to read MysticGeneration definition " + name + ".");
            return null;
        }
    }

    /** Mirrors {@code GenerationDefinition.toNativeRoleName}. */
    private static String toNativeRoleName(String definitionId) {
        int colon = definitionId.indexOf(':');
        return colon < 0
                ? definitionId
                : definitionId.substring(0, colon) + "_" + definitionId.substring(colon + 1);
    }

    /** Spawning and removal are store-thread confined; MysticGeneration asserts this itself. */
    private void onStoreThread(Store<EntityStore> store, Runnable action) {
        if (store.isInThread()) {
            action.run();
        } else {
            store.getExternalData().getWorld().execute(action);
        }
    }

    /** One MysticGeneration NPC, as MysticQuests needs to see it. */
    public record GenerationNpc(UUID uuid, String definitionId, int revision) {
    }

    /**
     * An indexed live NPC. Keyed on generation identity rather than {@code Ref}, because a role
     * change replaces the ref while the identity stays put.
     */
    private static final class IndexedNpc {
        private final String definitionId;
        private final Store<EntityStore> store;
        private final Ref<EntityStore> ref;
        private final long seenNanos;

        private IndexedNpc(String definitionId, Store<EntityStore> store, Ref<EntityStore> ref, long seenNanos) {
            this.definitionId = definitionId;
            this.store = store;
            this.ref = ref;
            this.seenNanos = seenNanos;
        }

        private boolean isLive() {
            return ref != null && ref.isValid() && System.nanoTime() - seenNanos <= RETENTION_NANOS;
        }
    }

    private record DefinitionMetadata(String id, String roleName, int revision) {
    }
}
