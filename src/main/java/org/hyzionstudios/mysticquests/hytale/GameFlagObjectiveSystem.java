package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.StoryWorld;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.DelayedEntitySystem;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Feeds {@code gameFlag} objectives and triggers. Game flags ({@code GameFlagsResource}) are
 * universe-wide integers the temple and the breaches write — {@code goblin_lab_generator_found},
 * {@code goblin_lab_power} — and nothing announces a change, so each player's quests are checked
 * against them about once a second, on the player's world thread like every other objective.
 */
public final class GameFlagObjectiveSystem extends DelayedEntitySystem<EntityStore> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    private static final float INTERVAL_SECONDS = 1f;

    private final Supplier<PlayerQuestService> quests;
    private final Supplier<StoryWorld> story;

    public GameFlagObjectiveSystem(Supplier<PlayerQuestService> quests, Supplier<StoryWorld> story) {
        super(INTERVAL_SECONDS);
        this.quests = quests;
        this.story = story;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Query.and(Player.getComponentType(), PlayerRef.getComponentType());
    }

    /** A quest completing here runs its rewards; one player at a time, as quest state expects. */
    @Override
    public boolean isParallel(int archetypeChunkSize, int taskCount) {
        return false;
    }

    @Override
    public void tick(float dt, int index, @Nonnull ArchetypeChunk<EntityStore> chunk, @Nonnull Store<EntityStore> store,
                     @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        PlayerQuestService service = quests.get();
        StoryWorld world = story.get();
        PlayerRef player = chunk.getComponent(index, PlayerRef.getComponentType());
        if (service == null || world == null || player == null) {
            return;
        }
        try {
            if (service.watchesGameFlags(player.getUuid())) {
                service.syncGameFlags(player.getUuid(), world::gameFlag);
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not check game flags for quest objectives.");
        }
    }
}
