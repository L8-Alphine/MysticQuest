package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Objects;

/**
 * A declared narrative tag.
 *
 * <p>Tags are flags: an owner has one or it does not. They carry provenance (who added them, when)
 * and may expire, but never a value. That is the specification's "tags must not be abused as
 * arbitrary value containers", and it is why a counter that v1 content encoded as
 * {@code keys_found_3} belongs in a variable instead.
 *
 * @param scope the one scope this tag lives in, or null for an undeclared tag in an open namespace
 * @param defaultTtl how long the tag lasts when the adding action gives no expiry; null for forever
 */
public record TagSchema(NamespacedId id, @Nullable VariableScope scope, @Nullable Duration defaultTtl, String description) {
    public TagSchema {
        Objects.requireNonNull(id, "id");
        description = description == null ? "" : description;
        if (defaultTtl != null && (defaultTtl.isNegative() || defaultTtl.isZero())) {
            throw new IllegalArgumentException("Tag " + id + " has a non-positive default ttl.");
        }
    }

    public boolean allows(VariableScope requested) {
        return scope == null || scope == requested;
    }
}
