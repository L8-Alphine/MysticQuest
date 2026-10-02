package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * A declared narrative variable: its type, its scope, and its value when nothing has been written.
 *
 * @param scope the one scope this variable lives in, or null for an undeclared variable in an open
 *         namespace, which may live in any scope
 * @param defaultValue what a read returns before the first write; null means "unset"
 */
public record VariableSchema(
        NamespacedId id,
        TypeSpec type,
        @Nullable VariableScope scope,
        @Nullable QuestValue defaultValue,
        String description) {

    public VariableSchema {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(type, "type");
        description = description == null ? "" : description;
        if (defaultValue != null && !ValueCodec.conforms(type, defaultValue)) {
            throw new IllegalArgumentException("Default for " + id + " is not a " + type + ".");
        }
    }

    /** Whether this variable may be stored under {@code requested}. */
    public boolean allows(VariableScope requested) {
        return scope == null || scope == requested;
    }
}
