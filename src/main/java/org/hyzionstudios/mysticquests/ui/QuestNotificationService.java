package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.service.PlayerInventoryService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.util.ChatColorUtil;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.UUID;
import java.util.logging.Level;
import java.util.function.UnaryOperator;

public final class QuestNotificationService implements PlayerInventoryService.QuestNotificationSink {
    private final PlayerSessionService sessionService;
    private final HytaleLogger logger;

    public QuestNotificationService(PlayerSessionService sessionService, HytaleLogger logger) {
        this.sessionService = sessionService;
        this.logger = logger;
    }

    public void send(UUID playerId, EventDefinition event) {
        send(playerId, event, UnaryOperator.identity());
    }

    public void send(UUID playerId, EventDefinition event, UnaryOperator<String> textResolver) {
        PlayerRef playerRef = sessionService.playerRef(playerId);
        if (playerRef == null) {
            return;
        }
        try {
            Message title = message(textResolver.apply(event.text("title", "MysticQuests")), event.text("titleColor", ""));
            Message body = message(textResolver.apply(event.text("body", event.text("message", ""))), event.text("bodyColor", ""));
            NotificationStyle style = style(event.text("style", "Default"));
            String icon = event.text("icon", "");
            ItemWithAllMetadata item = item(event);
            if (!icon.isBlank() || item != null) {
                NotificationUtil.sendNotification(
                        playerRef.getPacketHandler(),
                        title,
                        body,
                        icon.isBlank() ? null : icon,
                        item,
                        style);
            } else {
                NotificationUtil.sendNotification(playerRef.getPacketHandler(), title, body, style);
            }
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to send MysticQuests notification to " + playerId + ".");
        }
    }

    @Override
    public void sendChat(UUID playerId, String text, String color) {
        PlayerRef playerRef = sessionService.playerRef(playerId);
        if (playerRef == null) {
            return;
        }
        playerRef.sendMessage(ChatColorUtil.message(text, color));
    }

    private Message message(String text, String color) {
        return ChatColorUtil.message(text, color);
    }

    private NotificationStyle style(String rawStyle) {
        for (NotificationStyle style : NotificationStyle.values()) {
            if (style.name().equalsIgnoreCase(rawStyle)) {
                return style;
            }
        }
        return NotificationStyle.Default;
    }

    private ItemWithAllMetadata item(EventDefinition event) {
        String itemId = event.text("item", "");
        if (itemId.isBlank()) {
            return null;
        }
        int quantity = Math.max(1, event.integer("quantity", 1));
        return new ItemWithAllMetadata(itemId, quantity, 0.0D, 0.0D, 0, false, event.text("metadata", null));
    }
}
