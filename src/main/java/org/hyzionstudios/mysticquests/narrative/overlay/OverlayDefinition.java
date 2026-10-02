package org.hyzionstudios.mysticquests.narrative.overlay;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import java.util.Objects;

/**
 * One world overlay (§9): an entity placed in the world, typically a door, seal, rubble or invisible
 * wall with {@code HardCollision}, whose presence differs per audience.
 *
 * @param entity the placed entity, {@code uuid:<id>} or {@code generation:<id>}
 * @param presentByDefault whether audiences with no override see (and collide with) it; a seal is
 *         present by default and hidden for those who opened it, a secret bridge is absent by default
 *         and shown to those who earned it
 */
public record OverlayDefinition(NamespacedId id, String packageId, EntityRefValue entity, boolean presentByDefault,
                                String description) {
    public OverlayDefinition {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(entity, "entity");
        description = description == null ? "" : description;
    }
}
