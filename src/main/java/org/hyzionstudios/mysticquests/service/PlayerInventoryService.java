package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.EventDefinition;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.ItemStack;
import com.hypixel.hytale.server.core.inventory.container.CombinedItemContainer;

import java.util.UUID;
import java.util.logging.Level;

/**
 * Applies {@code giveItem} and {@code removeItem} quest events against the player inventory.
 * All work is marshalled onto the owning world thread by {@link PlayerSessionService}.
 */
public final class PlayerInventoryService {
    private final PlayerSessionService sessionService;
    private final QuestNotificationSink notificationSink;
    private final HytaleLogger logger;

    public PlayerInventoryService(
            PlayerSessionService sessionService,
            QuestNotificationSink notificationSink,
            HytaleLogger logger) {
        this.sessionService = sessionService;
        this.notificationSink = notificationSink;
        this.logger = logger;
    }

    public void give(UUID playerId, EventDefinition event) {
        ItemStack stack = stack(event);
        if (stack == null) {
            return;
        }
        sessionService.runOnWorld(playerId, (playerEntity, store) -> {
            try {
                CombinedItemContainer inventory = InventoryComponent.getCombined(
                        store, playerEntity, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
                if (!inventory.canAddItemStack(stack)) {
                    // Bible 11.2: warn before commit rather than silently dropping the reward.
                    notificationSink.sendChat(
                            playerId,
                            "Your inventory is full. Free a slot to receive " + stack.getQuantity() + "x " + stack.getItemId() + ".",
                            "#F59E3A");
                    return;
                }
                inventory.addItemStack(stack);
            } catch (RuntimeException exception) {
                logger.at(Level.WARNING).withCause(exception)
                        .log("Failed to give " + stack.getItemId() + " to " + playerId + ".");
            }
        });
    }

    public void remove(UUID playerId, EventDefinition event) {
        ItemStack stack = stack(event);
        if (stack == null) {
            return;
        }
        sessionService.runOnWorld(playerId, (playerEntity, store) -> {
            try {
                CombinedItemContainer inventory = InventoryComponent.getCombined(
                        store, playerEntity, InventoryComponent.HOTBAR_STORAGE_BACKPACK);
                inventory.removeItemStack(stack);
            } catch (RuntimeException exception) {
                logger.at(Level.WARNING).withCause(exception)
                        .log("Failed to remove " + stack.getItemId() + " from " + playerId + ".");
            }
        });
    }

    private ItemStack stack(EventDefinition event) {
        String itemId = event.text("item", event.text("id", ""));
        if (itemId.isBlank()) {
            return null;
        }
        return new ItemStack(itemId, Math.max(1, event.integer("amount", event.integer("quantity", 1))));
    }

    /** Narrow view of the notification service so inventory code does not depend on the UI package. */
    public interface QuestNotificationSink {
        void sendChat(UUID playerId, String text, String color);
    }
}
