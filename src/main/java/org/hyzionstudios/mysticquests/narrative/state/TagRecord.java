package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.Objects;

/**
 * One tag held by one owner, with the provenance the debugger shows.
 *
 * @param source what added it: an action path such as {@code puzzle:hyzion:druid_temple.keys}, a
 *         command such as {@code command:<staff uuid>}, or {@code migration:v1}
 * @param expiresAt when it stops counting; null for never
 */
public record TagRecord(NamespacedId id, Instant addedAt, @Nullable Instant expiresAt, String source) {
    public TagRecord {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(addedAt, "addedAt");
        source = source == null ? "" : source;
    }

    /** Expired tags are absent to every read, even before a sweep removes them. */
    public boolean expired(Instant now) {
        return expiresAt != null && !now.isBefore(expiresAt);
    }
}
