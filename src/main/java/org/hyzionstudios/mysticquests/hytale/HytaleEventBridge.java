package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.ui.QuestHudService;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
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
    }

    private void onReady(PlayerReadyEvent event) {
        sessionService.register(event.getPlayerRef(), event.getPlayer());
        runtime.registerOnlinePlayer(event.getPlayer().getPlayerRef());
        conversationService.registerPlayer(event.getPlayer().getUuid(), event.getPlayerRef());
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
            signalBus.publish(QuestSignal.targeted(playerId, "interactEntity", entityTarget, 1, entityContext(event)));
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
        if (event.getTriggerEventType() == TriggerEventType.ENTER) {
            signalBus.publish(QuestSignal.targeted(entityUuid, "triggerEnter", event.getVolumeId(), 1, triggerContext(event)));
        } else if (event.getTriggerEventType() == TriggerEventType.EXIT) {
            signalBus.publish(QuestSignal.targeted(entityUuid, "triggerExit", event.getVolumeId(), 1, triggerContext(event)));
        }
    }

    private void onDisconnect(PlayerDisconnectEvent event) {
        UUID playerId = event.getPlayerRef().getUuid();
        sessionService.unregister(playerId);
        runtime.unregisterOnlinePlayer(playerId);
        hudService.unregisterPlayer(playerId);
        conversationService.unregisterPlayer(playerId);
    }

    private QuestTargetContext entityContext(PlayerInteractEvent event) {
        String id = event.getTargetEntity().getUuid() == null ? null : event.getTargetEntity().getUuid().toString();
        return new QuestTargetContext(
                id,
                event.getTargetEntity().getClass().getSimpleName(),
                event.getTargetEntity().getLegacyDisplayName(),
                null,
                null,
                playerWorldId(event),
                null,
                null,
                null);
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

    private QuestTargetContext triggerContext(TriggerVolumeEvent event) {
        String world = event.getWorldName() == null ? "" : event.getWorldName();
        String volumeId = event.getVolumeId();
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
