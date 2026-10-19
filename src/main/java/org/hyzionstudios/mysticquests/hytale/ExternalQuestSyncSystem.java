package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestService;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Supplier;

/**
 * Re-reads every player's mirrored quests from the mods that run them, on the player's world thread,
 * which is where those mods keep their state (Endgame &amp; QoL's {@code QuestTrackerSystem} reads its
 * quest state from the same kind of player-ticking system).
 *
 * <p>The system ticks often and {@link ExternalQuestService#sync} decides per player whether a re-read
 * is due, so the configured interval applies and a Journal action can ask for an immediate one.
 */
public final class ExternalQuestSyncSystem extends DelayedEntitySystem<EntityStore> {
    private static final float TICK_SECONDS = 0.25f;

    private final Supplier<ExternalQuestService> service;

    public ExternalQuestSyncSystem(Supplier<ExternalQuestService> service) {
        super(TICK_SECONDS);
        this.service = service;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    /** One player at a time on the world thread: the mods read here do not expect concurrent readers. */
    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> archetypeChunk,
                     @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        ExternalQuestService external = service.get();
        if (external == null) {
            return;
        }
        Player player = archetypeChunk.getComponent(index, Player.getComponentType());
        PlayerRef playerRef = archetypeChunk.getComponent(index, PlayerRef.getComponentType());
        Ref<EntityStore> ref = archetypeChunk.getReferenceTo(index);
        if (player == null || playerRef == null || ref == null) {
            return;
        }
        external.sync(new ExternalQuestBridge.PlayerHandle(ref, store, playerRef, player), System.currentTimeMillis());
    }
}
