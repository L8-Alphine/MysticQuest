package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.ui.QuestHudService;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEventType;
import com.hypixel.hytale.builtin.triggervolumes.event.TriggerVolumeEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerCraftEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerDisconnectEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerReadyEvent;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;

public final class HytaleEventBridge {
    private final JavaPlugin plugin;
    private final QuestSignalBus signalBus;
    private final ConversationService conversationService;
    private final QuestHudService hudService;
    private final QuestNotificationService notificationService;

    public HytaleEventBridge(
            JavaPlugin plugin,
            QuestSignalBus signalBus,
            ConversationService conversationService,
            QuestHudService hudService,
            QuestNotificationService notificationService) {
        this.plugin = plugin;
        this.signalBus = signalBus;
        this.conversationService = conversationService;
        this.hudService = hudService;
        this.notificationService = notificationService;
    }

    public void register() {
        plugin.getEventRegistry().registerGlobal(PlayerReadyEvent.class, this::onReady);
        plugin.getEventRegistry().registerGlobal(PlayerCraftEvent.class, this::onCraft);
        plugin.getEventRegistry().registerGlobal(PlayerInteractEvent.class, this::onInteract);
        plugin.getEventRegistry().registerGlobal(TriggerVolumeEvent.class, this::onTriggerVolume);
        plugin.getEventRegistry().registerGlobal(PlayerDisconnectEvent.class, this::onDisconnect);
    }

    private void onReady(PlayerReadyEvent event) {
        notificationService.registerPlayer(event.getPlayer().getPlayerRef());
        hudService.registerPlayer(event.getPlayerRef(), event.getPlayer());
    }

    private void onCraft(PlayerCraftEvent event) {
        signalBus.publish(QuestSignal.simple(
                event.getPlayer().getUuid(),
                "craft",
                event.getCraftedRecipe().getId(),
                event.getQuantity()));
    }

    private void onInteract(PlayerInteractEvent event) {
        UUID playerId = event.getPlayer().getUuid();
        conversationService.tryStart(event);
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
        notificationService.unregisterPlayer(playerId);
        hudService.unregisterPlayer(playerId);
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
