package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.integration.triggervolumes.VolumeNames;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.ui.QuestHudService;
import org.hyzionstudios.mysticquests.ui.SpeakerPreviewService;

import com.hypixel.hytale.builtin.instances.config.InstanceWorldConfig;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.AddPlayerToWorldEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerCraftEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;

import java.util.UUID;

public final class HytaleEventBridge {
    private final MysticQuestsRuntime runtime;
    private final JavaPlugin plugin;
    private final QuestSignalBus signalBus;
    private final PlayerQuestService questService;
    private final ConversationService conversationService;
    private final QuestHudService hudService;
    private final PlayerSessionService sessionService;

    public HytaleEventBridge(
            MysticQuestsRuntime runtime,
            JavaPlugin plugin,
            QuestSignalBus signalBus,
            PlayerQuestService questService,
            ConversationService conversationService,
            QuestHudService hudService,
            PlayerSessionService sessionService) {
        this.runtime = runtime;
        this.plugin = plugin;
        this.signalBus = signalBus;
        this.questService = questService;
        this.conversationService = conversationService;
        this.hudService = hudService;
        this.sessionService = sessionService;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onReady);
        plugin.getEventRegistry().registerGlobal(PlayerCraftEvent.class, this::onCraft);
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
        plugin.getEventRegistry().registerGlobal(PlayerMouseMotionEvent.class, this::onMouseMotion);
        plugin.getEventRegistry().registerGlobal(PlayerInteractEvent.class, this::onInteract);
        plugin.getEventRegistry().registerGlobal(TriggerVolumeEvent.class, this::onTriggerVolume);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);
        plugin.getEventRegistry().registerGlobal(AddPlayerToWorldEvent.class, this::onAddToWorld);
    }

    /**
     * Feeds {@code enterInstance} objectives: the player arrived in a world. An instance copy answers
     * to the asset it was spawned from ({@code Forgotten_Temple_Goblins}, {@code Portals/Portals_Goblins}),
     * which {@code InstancesPlugin#spawnInstance} records in the world's {@link InstanceWorldConfig};
     * any other world to its name.
     *
     * <p>The event fires while the player is still being added, so the signal waits for the world's
     * next task: a quest it completes may give items, which needs the player in the world.
     */
    private void onAddToWorld(AddPlayerToWorldEvent event) {
        PlayerRef player = event.getHolder().getComponent(PlayerRef.getComponentType());
        World world = event.getWorld();
        if (player == null) {
            return;
        }
        // A world change leaves no dialogue card behind: hand the client its real game mode back.
        SpeakerPreviewService preview = conversationService.livePreview();
        if (preview != null) {
            Player playerComponent = event.getHolder().getComponent(Player.getComponentType());
            preview.hide(player, playerComponent == null ? null : playerComponent.getGameMode());
        }
        InstanceWorldConfig instance = InstanceWorldConfig.get(world.getWorldConfig());
        String target = instance != null && instance.getInstanceName() != null ? instance.getInstanceName() : world.getName();
        QuestTargetContext context = new QuestTargetContext(null, null, null, null, null, world.getName(), null, null, null);
        UUID playerId = player.getUuid();
        world.execute(() -> signalBus.publish(QuestSignal.targeted(playerId, "enterInstance", target, 1, context)));
    }

    private void onReady(PlayerReadyEvent event) {
        sessionService.register(event.getPlayerRef(), event.getPlayer());
        runtime.registerOnlinePlayer(event.getPlayer().getPlayerRef());
        conversationService.registerPlayer(event.getPlayer().getUuid(), event.getPlayerRef());
        if (runtime.narrative() != null) {
            // Restores the player's story sessions before anything can fire against them, and pays
            // out puzzle rewards that could not land while they were away.
            runtime.narrative().onJoin(event.getPlayer().getUuid());
        }
        // Before join quests start: a quest that starts on a first join is announced, while
        // everything the player already had is the baseline and announces nothing.
        runtime.transitionService().seed(event.getPlayer().getUuid());
        questService.startJoinQuests(event.getPlayer().getUuid());
        // Never reconcile() here: a HUD append on the ready tick is what disconnects the client with
        // "Could not find document …". reconcileAfterJoin holds it until the client has settled.
        hudService.reconcileAfterJoin(event.getPlayer().getUuid());
    }

    private void onCraft(PlayerCraftEvent event) {
        signalBus.publish(QuestSignal.simple(
                event.getPlayer().getUuid(),
                "craft",
                event.getCraftedRecipe().getId(),
                event.getQuantity()));
    }

    private void onMouseButton(PlayerMouseButtonEvent event) {
        if (conversationService.tryStart(event)) {
            event.setCancelled(true);
        }
        conversationService.reconcileInteractablesThrottled(event.getPlayerRefComponent().getUuid(), event.getPlayerRef());
    }

    private void onMouseMotion(PlayerMouseMotionEvent event) {
        conversationService.reconcileInteractablesThrottled(event.getPlayer().getUuid(), event.getPlayerRef());
    }

    /**
     * Only opens conversations. The engine no longer raises this deprecated event, so interaction
     * objectives come from {@link InteractObjectiveSystems} instead; publishing them here as well
     * would count every use twice should it ever fire again.
     */
    private void onInteract(PlayerInteractEvent event) {
        if (conversationService.tryStart(event)) {
            event.setCancelled(true);
        }
    }

    private void onTriggerVolume(TriggerVolumeEvent event) {
        UUID entityUuid = event.getEntityUuid();
        if (entityUuid == null) {
            return;
        }
        // The event carries the engine's generated id; content names the volume by its Name.
        String volume = VolumeNames.label(event.getEntityRef().getStore(), event.getVolumeId());
        // Logical activation decides first. A volume disabled for this player's session, the player
        // or their party feeds neither puzzles nor triggerEnter/triggerExit objectives, while it keeps
        // working for everyone else in the same place.
        if (runtime.narrative() != null && !runtime.narrative().onTrigger(
                event.getWorldName(), volume, event.getTriggerEventType().name(), entityUuid)) {
            return;
        }
        if (event.getTriggerEventType() == TriggerEventType.ENTER) {
            signalBus.publish(QuestSignal.targeted(entityUuid, "triggerEnter", volume, 1, triggerContext(event, volume)));
        } else if (event.getTriggerEventType() == TriggerEventType.EXIT) {
            signalBus.publish(QuestSignal.targeted(entityUuid, "triggerExit", volume, 1, triggerContext(event, volume)));
        }
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();
        sessionService.unregister(playerId);
        runtime.unregisterOnlinePlayer(playerId);
        hudService.unregisterPlayer(playerId);
        runtime.transitionService().forget(playerId);
        conversationService.unregisterPlayer(playerId);
        // A trial never waits on someone who has left: it resolves as abandoned, and the mod that
        // started it (MysticRPG) is told at once.
        if (runtime.trials() != null) {
            runtime.trials().onDisconnect(playerId);
        }
        if (runtime.narrative() != null) {
            // Writes and unloads the player's story sessions, so a transfer to another server that
            // shares the narrative store picks them up exactly as they were left.
            runtime.narrative().onQuit(playerId);
        }
        // Persist inside the disconnect rather than waiting out the debounce window, so a player who
        // logs off immediately after a quest step does not lose it to a server stop seconds later.
        runtime.flushState();
    }

    private QuestTargetContext triggerContext(TriggerVolumeEvent event, String volumeId) {
        String world = event.getWorldName() == null ? "" : event.getWorldName();
        String volumeKey = world.isBlank() ? volumeId : world + ":" + volumeId;
        String entityId = event.getEntityUuid() == null ? null : event.getEntityUuid().toString();
        return new QuestTargetContext(
                entityId,
                null,
                null,
                null,
                null,
                world,
                volumeId,
                volumeKey,
                world);
    }
}
