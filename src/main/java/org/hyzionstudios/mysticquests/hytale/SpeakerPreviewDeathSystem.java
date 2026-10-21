package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.ui.SpeakerPreviewService;

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
 * Gives a player who dies mid-dialogue their real game mode back.
 *
 * <p>The death screen replaces the dialogue page, which already restores it
 * ({@code DeathSystems.PlayerDeathScreen#openDeathScreen} opens a page, and the page manager
 * dismisses the old one). A death with no death screen leaves the page open, so this catches that
 * case. Runs on the world thread, as every death system does.
 */
public final class SpeakerPreviewDeathSystem extends DeathSystems.OnDeathSystem {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private final Supplier<SpeakerPreviewService> preview;

    public SpeakerPreviewDeathSystem(Supplier<SpeakerPreviewService> preview) {
        this.preview = preview;
    }

    @Nonnull
    @Override
    public Query<EntityStore> getQuery() {
        return Player.getComponentType();
    }

    @Override
    public void onComponentAdded(@Nonnull Ref<EntityStore> ref, @Nonnull DeathComponent death,
                                 @Nonnull Store<EntityStore> store, @Nonnull CommandBuffer<EntityStore> commandBuffer) {
        SpeakerPreviewService service = preview.get();
        if (service == null) {
            return;
        }
        try {
            PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
            if (playerRef != null && service.spoofing(playerRef.getUuid())) {
                service.hide(playerRef, ref, store);
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not clear a dead player's dialogue preview.");
        }
    }
}
