package org.hyzionstudios.mysticquests.narrative.overlay;

import org.hyzionstudios.mysticquests.narrative.PresentationLayer;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService.Decision;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Per-audience world presentation (§9): which overlay entities each player is shown, and so, for
 * hitbox entities, where each player can walk.
 *
 * <h2>Why entities, and why it does not rubber-band</h2>
 *
 * <p>On {@code release/0.6.8} (and {@code 0.7.0-pre.3.1}) the server does not correct player movement
 * against collisions: {@code PlayerProcessMovementSystem} has its motion-path collision pass switched
 * off, and nothing on the server reads {@code HitboxCollision}. Player collision is resolved by the
 * client, against what that client has been sent. Hitbox data reaches a client only through the
 * entity tracker, and only for entities it can see ({@code HitboxCollisionSystems.EntityTrackerUpdate}).
 *
 * <p>So an overlay entity carrying {@code HardCollision} is a wall for exactly the players it is shown
 * to, and passable for everyone else, with no server/client disagreement to snap anyone back. Build
 * the world <em>open</em> and let overlays close it per audience. Never build it closed and try to open
 * it per audience: a client cannot be told a server-solid block is air without block effects (damage,
 * triggers) diverging.
 *
 * <p>State is the trigger activation layering under the key {@code #overlay/<id>}: session, then
 * player, then party, then global, with "disabled wins" between sessions. Overlays therefore persist
 * exactly like logical trigger overrides, and the same {@code scope} values apply to their actions.
 */
public final class WorldOverlayRegistry implements PresentationLayer {
    private static final String KEY_PREFIX = "#overlay/";

    private final Supplier<Map<NamespacedId, OverlayDefinition>> definitions;
    private final TriggerActivationService activation;
    /** Live entity UUID to overlay. UUID overlays bind at install; generation ones on observation. */
    private final Map<UUID, OverlayDefinition> live = new ConcurrentHashMap<>();
    private volatile Map<String, OverlayDefinition> byGenerationId = Map.of();

    public WorldOverlayRegistry(Supplier<Map<NamespacedId, OverlayDefinition>> definitions, TriggerActivationService activation) {
        this.definitions = definitions;
        this.activation = activation;
    }

    /** Re-binds after a content reload: drops every binding and binds UUID overlays again. */
    public void rebind() {
        live.clear();
        Map<String, OverlayDefinition> generation = new ConcurrentHashMap<>();
        for (OverlayDefinition overlay : definitions.get().values()) {
            switch (overlay.entity().kind()) {
                case "uuid" -> live.put(UUID.fromString(overlay.entity().id()), overlay);
                case "generation" -> generation.put(overlay.entity().id(), overlay);
                default -> {
                }
            }
        }
        byGenerationId = Map.copyOf(generation);
    }

    /** The activation key an overlay's per-audience state is stored under. */
    public static String key(NamespacedId overlay) {
        return KEY_PREFIX + overlay;
    }

    /** Whether {@code viewer} is currently shown this overlay. */
    public boolean presentFor(UUID viewer, OverlayDefinition overlay) {
        Decision decision = activation.decide(key(overlay.id()), viewer);
        return decision.decidedBy() == null ? overlay.presentByDefault() : decision.enabled();
    }

    @Override
    public boolean isEmpty() {
        return live.isEmpty();
    }

    @Override
    public boolean hides(UUID viewer, UUID entity) {
        OverlayDefinition overlay = live.isEmpty() ? null : live.get(entity);
        return overlay != null && !presentFor(viewer, overlay);
    }

    @Override
    public Set<UUID> hiddenFrom(UUID viewer) {
        if (live.isEmpty()) {
            return Set.of();
        }
        Set<UUID> hidden = new HashSet<>();
        live.forEach((entity, overlay) -> {
            if (!presentFor(viewer, overlay)) {
                hidden.add(entity);
            }
        });
        return hidden;
    }

    @Override
    public void observe(UUID entity, UUID generationIdentity) {
        if (entity == null || generationIdentity == null) {
            return;
        }
        OverlayDefinition overlay = byGenerationId.get(generationIdentity.toString());
        if (overlay != null && live.get(entity) != overlay) {
            live.put(entity, overlay);
        }
    }

    @Override
    public boolean wantsObservation() {
        return !byGenerationId.isEmpty();
    }
}
