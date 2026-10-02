package org.hyzionstudios.mysticquests.narrative;

import java.util.Set;
import java.util.UUID;

/**
 * A per-viewer rule about which entities a player is shown.
 *
 * <p>Story entities (§8) and world overlays (§9) are both layers. {@code VisibilityService} folds
 * every layer into what the entity and nameplate systems present to each viewer. On this engine, an
 * entity a client is not shown also does not collide with that client, because player collision is
 * resolved client-side (see gap-analysis Phase 8 notes). A layer is therefore how MysticQuests
 * decides both what a player sees and, for hitbox entities, where they can walk.
 */
public interface PresentationLayer {
    /** Nothing to hide for anyone; lets the per-tick systems skip work. */
    boolean isEmpty();

    /** The entities {@code viewer} must not be shown right now. */
    Set<UUID> hiddenFrom(UUID viewer);

    /** Whether {@code entity} must not be shown to {@code viewer}. */
    boolean hides(UUID viewer, UUID entity);

    /**
     * Binds a live entity to a durable identity, called for every indexed entity each tick. Layers
     * that name entities by MysticGeneration identity use it to follow republishes.
     */
    default void observe(UUID entity, UUID generationIdentity) {
    }

    /** Whether {@link #observe} has anything to do, so the identity read is skipped otherwise. */
    default boolean wantsObservation() {
        return false;
    }
}
