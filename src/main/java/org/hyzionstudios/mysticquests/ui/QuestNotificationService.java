package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.EventDefinition;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ItemWithAllMetadata;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class QuestNotificationService {
    private final HytaleLogger logger;
    private final Map<UUID, PlayerRef> players = new ConcurrentHashMap<>();

    public QuestNotificationService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void registerPlayer(PlayerRef playerRef) {
        if (playerRef != null) {
            players.put(playerRef.getUuid(), playerRef);
        }
    }

    public void unregisterPlayer(UUID playerId) {
        players.remove(playerId);
    }

    public void clear() {
        players.clear();
    }

    public void send(UUID playerId, EventDefinition event) {
        PlayerRef playerRef = players.get(playerId);
        if (playerRef == null) {
            return;
        }
        try {
            Message title = message(event.text("title", "MysticQuests"), event.text("titleColor", ""));
            Message body = message(event.text("body", event.text("message", "")), event.text("bodyColor", ""));
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

    private Message message(String text, String color) {
        Message message = text == null ? Message.empty() : Message.parse(text);
        if (color != null && !color.isBlank()) {
            message.color(color);
        }
        return message;
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
        return new ItemWithAllMetadata(itemId, quantity, 0.0D, 0.0D, false, event.text("metadata", null));
    }
}
