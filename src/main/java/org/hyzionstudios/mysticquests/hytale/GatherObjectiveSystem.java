package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.EntityEventSystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.ecs.InventoryChangeEvent;
import com.hypixel.hytale.server.core.inventory.InventoryComponent;
import com.hypixel.hytale.server.core.inventory.container.ItemContainer;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code gather} quest objectives from the player's inventory, the way Hytale's own
 * {@code ObjectiveInventoryChangeSystem} feeds its gather tasks: on every inventory change, each
 * gather objective is set to how many of its item the player holds (hotbar first, then the rest).
 *
 * <p>Picking an item up off the ground raises no event in this engine, so counting held items is
 * the reliable signal; it also counts crafted, traded and rewarded items.
 *
 * <p><b>Runs on the world thread.</b> {@link #sync} is also called when a quest starts, so items the
 * player already holds count at once.
 */
public final class GatherObjectiveSystem extends EntityEventSystem<EntityStore, InventoryChangeEvent> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    /** Completing a quest saves, which can call {@link #sync} again from the same stack. */
    private static final ThreadLocal<Boolean> SYNCING = ThreadLocal.withInitial(() -> false);

    private final Supplier<PlayerQuestService> quests;

    public GatherObjectiveSystem(Supplier<PlayerQuestService> quests) {
        super(InventoryChangeEvent.class);
        this.quests = quests;
    }

    @Override
    public void handle(int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk, @Nonnull Store<EntityStore> store,
                       @Nonnull CommandBuffer<EntityStore> commandBuffer, @Nonnull InventoryChangeEvent event) {
        sync(quests.get(), archetypeChunk.getReferenceTo(index), store);
    }

    /** Recounts the gather objectives of one player; does nothing when they have none. */
    public static void sync(PlayerQuestService quests, Ref<EntityStore> player, ComponentAccessor<EntityStore> accessor) {
        if (quests == null || player == null || !player.isValid() || SYNCING.get()) {
            return;
        }
        SYNCING.set(true);
        try {
            PlayerRef ref = accessor.getComponent(player, PlayerRef.getComponentType());
            UUID playerId = ref == null ? null : ref.getUuid();
            if (playerId == null || !quests.hasGatherObjectives(playerId)) {
                return;
            }
            ItemContainer inventory = InventoryComponent.getCombined(accessor, player, InventoryComponent.HOTBAR_FIRST);
            quests.syncHeldItems(playerId, item -> inventory.countItemStacks(stack -> item.equals(stack.getItemId())));
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not update gather objectives.");
        } finally {
            SYNCING.set(false);
        }
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }
}
