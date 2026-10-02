package org.hyzionstudios.mysticquests.narrative.trigger;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

/**
 * A trigger volume firing, normalised away from the engine's types (§5.1, "Normalized MysticQuests
 * TriggerEvent").
 *
 * <p>The Hytale adapter builds these from {@code TriggerVolumeEvent} and {@code TriggerContext}. Nothing
 * past the adapter touches engine classes, so session scoping, activation and puzzle routing are
 * plain logic that unit tests can drive directly.
 *
 * @param volumeKey {@code world:volumeId}, the same key v1 uses for volume-scoped state
 * @param eventType the engine's event name: {@code ENTER}, {@code EXIT}, {@code SIGNAL_RECEIVED}, …
 * @param player the triggering player, or null when a non-player entity fired it
 * @param signalTags tags carried by a signal event; empty otherwise
 */
public record TriggerEvent(String volumeKey, String eventType, @Nullable UUID player,
                           @Nullable UUID entity, List<String> signalTags) {
    public TriggerEvent {
        Objects.requireNonNull(volumeKey, "volumeKey");
        Objects.requireNonNull(eventType, "eventType");
        signalTags = signalTags == null ? List.of() : List.copyOf(signalTags);
    }

    public static String volumeKey(@Nullable String world, String volumeId) {
        return world == null || world.isBlank() ? volumeId : world + ":" + volumeId;
    }
}
