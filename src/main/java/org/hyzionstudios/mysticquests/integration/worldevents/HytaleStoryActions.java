package org.hyzionstudios.mysticquests.integration.worldevents;

import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.StoryWorld;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.adventure.worldevents.GlobalWorldEventManager;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventRecord;
import com.hypixel.hytale.builtin.adventure.worldevents.WorldEventsConfig;
import com.hypixel.hytale.builtin.adventure.worldevents.action.EventStartAction;
import com.hypixel.hytale.builtin.adventure.worldevents.action.EventStopAction;
import com.hypixel.hytale.builtin.adventure.worldevents.component.WorldEventSignal;
import com.hypixel.hytale.builtin.adventure.worldevents.event.WorldEventAsset;
import com.hypixel.hytale.builtin.encountermanager.EncounterManager;
import com.hypixel.hytale.builtin.encountermanager.EncounterManagerPlugin;
import com.hypixel.hytale.builtin.gameflags.GameFlagsResource;
import com.hypixel.hytale.component.AddReason;
import com.hypixel.hytale.component.Holder;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.nameplate.Nameplate;
import com.hypixel.hytale.server.core.modules.entity.component.HiddenFromAdventurePlayers;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.component.PersistentModel;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.npc.NPCPlugin;
import com.hypixel.hytale.server.npc.asset.builder.BuilderInfo;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * What quests can do to Hytale's own story systems: start and stop world events (the goblin breaches),
 * signal them, read and write game flags, and place an encounter (a boss fight or a breach pack).
 *
 * <p>Every call mirrors one the engine itself makes, so a quest drives the story exactly as the game's
 * own content does:
 * <ul>
 *   <li>a local event starts as {@code GlobalWorldEventManager#startEventInAllWorlds} starts one,
 *       through {@link EventStartAction#startEvent};</li>
 *   <li>a global event is switched on by id, as the temple generator's {@code GlobalEventStartInteraction}
 *       does, and then runs in every world whose gameplay config lists it;</li>
 *   <li>a signal is the {@link WorldEventSignal} an encounter's {@code SignalWorldEvent} action sends;</li>
 *   <li>a flag write is {@code SetGameFlagInteraction#firstRun}: set or raise, then flush;</li>
 *   <li>an encounter is placed as {@code /encounter add} places one ({@code EncounterCommand.Add}).</li>
 * </ul>
 *
 * <p>World changes are always queued onto the player's world with {@link World#execute}, never run
 * inline: a quest action can fire from inside an ECS system (a kill completing a quest), where adding
 * an entity or invoking an event on the store directly is not allowed.
 */
public final class HytaleStoryActions implements StoryWorld {
    private final PlayerSessionService sessions;
    private final WorldEventTracker tracker;
    private final HytaleLogger logger;

    public HytaleStoryActions(PlayerSessionService sessions, WorldEventTracker tracker, HytaleLogger logger) {
        this.sessions = sessions;
        this.tracker = tracker;
        this.logger = logger;
    }

    public WorldEventTracker tracker() {
        return tracker;
    }

    /**
     * Starts a world event.
     *
     * @param asset the world-event asset, e.g. {@code Goblin_Breach_First_Event}; may be null with a global id
     * @param global a global event id (a UUID listed under {@code GlobalEvents} in the gameplay config), or
     *               {@code "true"} to look the asset's id up there; null starts the asset in the player's world only
     */
    @Override
    public void startWorldEvent(UUID playerId, @Nullable String asset, @Nullable String global) {
        if (global != null && !global.isBlank()) {
            onWorld(playerId, (ref, store) -> {
                UUID id = globalId(store, asset, global);
                if (id == null) {
                    logger.at(Level.WARNING).log("startWorldEvent: no global event " + describe(asset, global)
                            + " in the gameplay config of world " + store.getExternalData().getWorld().getName() + ".");
                } else if (!GlobalWorldEventManager.startEvent(id)) {
                    logger.at(Level.FINE).log("startWorldEvent: global event " + id + " is already running.");
                }
            });
            return;
        }
        if (asset == null || asset.isBlank()) {
            logger.at(Level.WARNING).log("A startWorldEvent action named no event.");
            return;
        }
        if (WorldEventAsset.getAssetOrUnknown(asset) == WorldEventAsset.UNKNOWN) {
            logger.at(Level.WARNING).log("startWorldEvent: there is no world event named " + asset + ".");
            return;
        }
        // Transient and id-less, as a quest may start the same event again later: a non-transient run
        // is recorded complete in the world and could never start twice.
        onWorld(playerId, (ref, store) -> {
            // An event placed with MysticQuestsPlayerLocation opens near this player, not a random one.
            QuestPlayerLocation.anchor(store.getExternalData().getWorld().getName(), playerId);
            if (EventStartAction.startEvent(store, new WorldEventRecord(null, asset, true)) == null) {
                logger.at(Level.INFO).log("startWorldEvent: " + asset + " did not start in "
                        + store.getExternalData().getWorld().getName() + " (its instance limit may be reached).");
            }
        });
    }

    /** Stops a world event in the player's world, or switches a global one off everywhere. */
    @Override
    public void stopWorldEvent(UUID playerId, @Nullable String asset, @Nullable String global) {
        if (global != null && !global.isBlank()) {
            onWorld(playerId, (ref, store) -> {
                UUID id = globalId(store, asset, global);
                if (id != null) {
                    GlobalWorldEventManager.stopEvent(id);
                }
            });
            return;
        }
        if (asset == null || asset.isBlank()) {
            logger.at(Level.WARNING).log("A stopWorldEvent action named no event.");
            return;
        }
        onWorld(playerId, (ref, store) ->
                EventStopAction.stopEvent(store, asset, (event, wanted) -> event.asset.equals(wanted)));
    }

    /**
     * Sends a world-event signal in the player's world, completing every running stage that waits for it
     * with a {@code SignalCondition} — the same signal a breach pack sends once it is beaten.
     */
    @Override
    public void signalWorldEvent(UUID playerId, String signalId) {
        if (signalId == null || signalId.isBlank()) {
            logger.at(Level.WARNING).log("A signalWorldEvent action named no signal.");
            return;
        }
        onWorld(playerId, (ref, store) -> store.invoke(ref, new WorldEventSignal(null, signalId)));
    }

    /** A game flag's level; 0 when it was never set. Safe from any thread. */
    @Override
    public int gameFlag(String flag) {
        if (flag == null || flag.isBlank()) {
            return 0;
        }
        try {
            return GameFlagsResource.get().getLevel(flag);
        } catch (RuntimeException | LinkageError unavailable) {
            return 0;
        }
    }

    /**
     * Writes a game flag.
     *
     * @param mode {@code set} overwrites, {@code raise} only ever raises (earned progression),
     *             {@code remove} clears it back to 0
     */
    @Override
    public void setGameFlag(String flag, int value, String mode) {
        if (flag == null || flag.isBlank()) {
            logger.at(Level.WARNING).log("A setGameFlag action named no flag.");
            return;
        }
        try {
            GameFlagsResource flags = GameFlagsResource.get();
            switch (mode == null ? "set" : mode.toLowerCase(Locale.ROOT)) {
                case "raise" -> flags.raise(flag, value);
                case "remove", "clear" -> flags.remove(flag);
                default -> flags.set(flag, value);
            }
            GameFlagsResource.flush();
        } catch (RuntimeException | LinkageError unavailable) {
            logger.at(Level.WARNING).withCause(unavailable).log("Could not write game flag " + flag + ".");
        }
    }

    /**
     * Places an encounter manager — a boss fight, a breach pack — where the player stands, or at the
     * given coordinates in the player's world.
     *
     * @param position x, y, z to place it at instead of the player's feet, or null
     */
    @Override
    public void startEncounter(UUID playerId, String encounterId, @Nullable double[] position) {
        if (encounterId == null || encounterId.isBlank()) {
            logger.at(Level.WARNING).log("A startEncounter action named no encounter.");
            return;
        }
        onWorld(playerId, (ref, store) -> {
            NPCPlugin npc = NPCPlugin.get();
            int index = npc.getIndex(encounterId);
            BuilderInfo info = index == AssetMapWithIndexes.NOT_FOUND ? null : npc.getBuilderManager().tryGetBuilderInfo(index);
            if (info == null || info.getBuilder().category() != EncounterManager.class) {
                logger.at(Level.WARNING).log("startEncounter: there is no encounter named " + encounterId + ".");
                return;
            }
            if (!info.getBuilder().isSpawnable()) {
                logger.at(Level.WARNING).log("startEncounter: " + encounterId + " is a template and cannot be placed.");
                return;
            }
            TransformComponent transform = store.getComponent(ref, TransformComponent.getComponentType());
            if (transform == null) {
                return;
            }
            TransformComponent placed = transform.clone();
            if (position != null && position.length == 3) {
                placed.getPosition().set(new Vector3d(position[0], position[1], position[2]));
            }
            Holder<EntityStore> holder = EntityStore.REGISTRY.newHolder();
            holder.addComponent(EncounterManager.getComponentType(), new EncounterManager(info.getKeyName(), info.getIndex()));
            holder.addComponent(TransformComponent.getComponentType(), placed);
            holder.addComponent(Nameplate.getComponentType(), new Nameplate(info.getKeyName()));
            holder.ensureComponent(UUIDComponent.getComponentType());
            holder.ensureComponent(HiddenFromAdventurePlayers.getComponentType());
            Model model = EncounterManagerPlugin.get().getMarkerModel();
            holder.addComponent(ModelComponent.getComponentType(), new ModelComponent(model));
            holder.addComponent(PersistentModel.getComponentType(), new PersistentModel(model.toReference()));
            Ref<EntityStore> spawned = store.addEntity(holder, AddReason.SPAWN);
            if (spawned == null || !spawned.isValid()) {
                logger.at(Level.WARNING).log("startEncounter: " + encounterId + " could not be placed.");
            }
        });
    }

    /**
     * Whether an event made from {@code asset} runs in the player's world, or in any world.
     * Read from what the world-event systems last saw, so it is safe from any thread.
     */
    @Override
    public boolean worldEventActive(UUID playerId, String asset, boolean anywhere) {
        if (asset == null || asset.isBlank()) {
            return false;
        }
        if (anywhere) {
            return tracker.activeAnywhere(asset);
        }
        PlayerRef player = sessions.playerRef(playerId);
        World world = player == null ? null : worldOf(player);
        return world != null && tracker.active(world.getName(), asset);
    }

    /**
     * Resolves the id of a global event: a literal UUID, or — given {@code "true"} — the id the world's
     * gameplay config lists for {@code asset}, so content can say "the global first breach" without
     * copying a UUID out of {@code GameplayConfigs/Default.json}.
     */
    @Nullable
    private static UUID globalId(Store<EntityStore> store, @Nullable String asset, String global) {
        if (!global.equalsIgnoreCase("true")) {
            try {
                return UUID.fromString(global.strip());
            } catch (IllegalArgumentException notAUuid) {
                return null;
            }
        }
        if (asset == null || asset.isBlank()) {
            return null;
        }
        WorldEventsConfig config = store.getExternalData().getWorld().getGameplayConfig()
                .getPluginConfig().get(WorldEventsConfig.class);
        if (config == null) {
            return null;
        }
        for (WorldEventRecord record : config.globalEvents()) {
            if (record.id() != null && asset.equals(record.event())) {
                return record.id();
            }
        }
        return null;
    }

    private static String describe(@Nullable String asset, String global) {
        return global.equalsIgnoreCase("true") ? "for " + asset : global;
    }

    @Nullable
    private static World worldOf(PlayerRef player) {
        Ref<EntityStore> ref = player.getReference();
        return ref == null || !ref.isValid() ? null : ref.getStore().getExternalData().getWorld();
    }

    /** Queues {@code action} on the player's world thread, even when already on it. */
    private void onWorld(UUID playerId, BiConsumer<Ref<EntityStore>, Store<EntityStore>> action) {
        PlayerRef player = sessions.playerRef(playerId);
        Ref<EntityStore> ref = player == null ? null : player.getReference();
        if (ref == null || !ref.isValid()) {
            logger.at(Level.FINE).log("Skipped a world-event action for offline player " + playerId + ".");
            return;
        }
        Store<EntityStore> store = ref.getStore();
        store.getExternalData().getWorld().execute(() -> {
            if (!ref.isValid()) {
                return;
            }
            try {
                action.accept(ref, store);
            } catch (RuntimeException | LinkageError failure) {
                logger.at(Level.WARNING).withCause(failure).log("A world-event action failed for " + playerId + ".");
            }
        });
    }
}
