package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.Damage;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Hands the death of a claimed story entity to the narrative runtime (§8 "which audience owns the
 * results"), which runs the claim's {@code onDeath} actions for the owning audience and releases it.
 *
 * <p>Fed by the same engine death hook as {@link KillObjectiveSystem}, with the killer read the same
 * way. The actions run after the tick on the world's own thread, not inside the death system, because
 * they may change entities and state the systems are iterating.
 */
public final class StoryEntityDeathSystem extends DeathSystems.OnDeathSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private static final Query<EntityStore> VICTIMS = Query.and(
            TransformComponent.getComponentType(),
            UUIDComponent.getComponentType(),
            Query.not(Player.getComponentType()));

    private final Supplier<NarrativeRuntime> narrative;

    public StoryEntityDeathSystem(Supplier<NarrativeRuntime> narrative) {
        this.narrative = narrative;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return VICTIMS;
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> victim, @Nonnull DeathComponent death,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null || runtime.storyEntities().isEmpty()) {
            return;
        }
        UUIDComponent uuid = store.getComponent(victim, UUIDComponent.getComponentType());
        if (uuid == null || uuid.getUuid() == null || runtime.storyEntities().claimOf(uuid.getUuid()) == null) {
            return;
        }
        UUID entity = uuid.getUuid();
        UUID killer = killer(death, store);
        World world = store.getExternalData().getWorld();
        String worldName = world.getName();
        world.execute(() -> {
            try {
                runtime.onStoryEntityDeath(entity, killer, worldName);
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("A story entity's onDeath actions failed.");
            }
        });
    }

    private static UUID killer(DeathComponent death, Store<EntityStore> store) {
        Damage damage = death.getDeathInfo();
        if (damage == null || !(damage.getSource() instanceof Damage.EntitySource source)) {
            return null;
        }
        Ref<EntityStore> ref = source.getRef();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        PlayerRef player = store.getComponent(ref, PlayerRef.getComponentType());
        return player == null ? null : player.getUuid();
    }
}
