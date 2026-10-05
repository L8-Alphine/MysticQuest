package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge.GenerationNpc;
import org.hyzionstudios.mysticquests.integration.triggervolumes.VolumeNames;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.ui.QuestHudService;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerCraftEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseMotionEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;

public final class HytaleEventBridge {
    private final MysticQuestsRuntime runtime;
    private final JavaPlugin plugin;
    private final QuestSignalBus signalBus;
    private final PlayerQuestService questService;
    private final ConversationService conversationService;
    private final QuestHudService hudService;
    private final PlayerSessionService sessionService;
    private final MysticGenerationBridge generationBridge;

    public HytaleEventBridge(
            MysticQuestsRuntime runtime,
            JavaPlugin plugin,
            QuestSignalBus signalBus,
            PlayerQuestService questService,
            ConversationService conversationService,
            QuestHudService hudService,
            PlayerSessionService sessionService,
            MysticGenerationBridge generationBridge) {
        this.runtime = runtime;
        this.plugin = plugin;
        this.signalBus = signalBus;
        this.questService = questService;
        this.conversationService = conversationService;
        this.hudService = hudService;
        this.sessionService = sessionService;
        this.generationBridge = generationBridge;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onReady);
        plugin.getEventRegistry().registerGlobal(PlayerCraftEvent.class, this::onCraft);
        plugin.getEventRegistry().registerGlobal(PlayerMouseButtonEvent.class, this::onMouseButton);
        plugin.getEventRegistry().registerGlobal(PlayerMouseMotionEvent.class, this::onMouseMotion);
        plugin.getEventRegistry().registerGlobal(PlayerInteractEvent.class, this::onInteract);
        plugin.getEventRegistry().registerGlobal(TriggerVolumeEvent.class, this::onTriggerVolume);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);
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

    private void onInteract(PlayerInteractEvent event) {
        UUID playerId = event.getPlayer().getUuid();
        if (conversationService.tryStart(event)) {
            event.setCancelled(true);
            return;
        }
        if (event.getTargetEntity() != null) {
            String entityTarget = event.getTargetEntity().getLegacyDisplayName();
            if (entityTarget == null || entityTarget.isBlank()) {
                entityTarget = event.getTargetEntity().getUuid().toString();
            }
            GenerationNpc npc = identify(event);
            QuestTargetContext context = entityContext(event, npc);
            signalBus.publish(QuestSignal.targeted(playerId, "interactEntity", entityTarget, 1, context));
            if (npc != null) {
                // A second, separate signal rather than a different target on the first: existing
                // interactEntity content keyed on display name must keep working unchanged.
                signalBus.publish(QuestSignal.targeted(playerId, "interactNpc", npc.definitionId(), 1, context));
            }
        }
        if (event.getTargetBlock() != null) {
            signalBus.publish(QuestSignal.targeted(playerId, "interactObject", event.getTargetBlock().toString(), 1, blockContext(event)));
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
        if (runtime.narrative() != null) {
            // Writes and unloads the player's story sessions, so a transfer to another server that
            // shares the narrative store picks them up exactly as they were left.
            runtime.narrative().onQuit(playerId);
        }
        // Persist inside the disconnect rather than waiting out the debounce window, so a player who
        // logs off immediately after a quest step does not lose it to a server stop seconds later.
        runtime.flushState();
    }

    /** The MysticGeneration identity of the interacted entity, or null when it has none. */
    private GenerationNpc identify(PlayerInteractEvent event) {
        if (generationBridge == null) {
            return null;
        }
        Ref<EntityStore> targetRef = event.getTargetRef();
        if (targetRef == null || !targetRef.isValid()) {
            return null;
        }
        return generationBridge.identify(targetRef.getStore(), targetRef).orElse(null);
    }

    private QuestTargetContext entityContext(PlayerInteractEvent event, GenerationNpc npc) {
        String id = event.getTargetEntity().getUuid() == null ? null : event.getTargetEntity().getUuid().toString();
        QuestTargetContext context = new QuestTargetContext(
                id,
                event.getTargetEntity().getClass().getSimpleName(),
                event.getTargetEntity().getLegacyDisplayName(),
                null,
                null,
                playerWorldId(event),
                null,
                null,
                null);
        return npc == null
                ? context
                : context.withGeneration(npc.definitionId(), npc.uuid().toString());
    }

    private QuestTargetContext blockContext(PlayerInteractEvent event) {
        PlayerRef playerRef = event.getPlayer().getPlayerRef();
        String worldId = playerRef == null || playerRef.getWorldUuid() == null ? "" : playerRef.getWorldUuid().toString();
        String blockId = worldId + ":" + event.getTargetBlock().x + ":" + event.getTargetBlock().y + ":" + event.getTargetBlock().z;
        return new QuestTargetContext(
                null,
                null,
                null,
                blockId,
                event.getTargetBlock().toString(),
                worldId,
                null,
                null,
                null);
    }

    private String playerWorldId(PlayerInteractEvent event) {
        PlayerRef playerRef = event.getPlayer().getPlayerRef();
        return playerRef == null || playerRef.getWorldUuid() == null ? "" : playerRef.getWorldUuid().toString();
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
