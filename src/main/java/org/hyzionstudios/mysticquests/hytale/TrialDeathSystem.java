package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.trial.QuestTrialService;

import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathComponent;
import com.hypixel.hytale.server.core.modules.entity.damage.DeathSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Counts a player's deaths against the trials they are running: a trial's {@code maxDeaths} fails it,
 * and deaths lower a ranked trial's score. Same hook as {@link SpeakerPreviewDeathSystem}: the
 * engine adds a {@link DeathComponent} to the player. Runs on the world thread, as every death
 * system does.
 */
public final class TrialDeathSystem extends DeathSystems.OnDeathSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Supplier<QuestTrialService> trials;

    public TrialDeathSystem(Supplier<QuestTrialService> trials) {
        this.trials = trials;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        QuestTrialService service = trials.get();
        if (service == null) {
            return;
        }
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null) {
                // After this tick: failing the trial changes quest state, whose listeners touch the
                // store, which must not happen while the death systems are still running.
                java.util.UUID player = playerRef.getUuid();
                store.getExternalData().getWorld().execute(() -> service.recordDeath(player));
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not count a death against a trial.");
        }
    }
}
