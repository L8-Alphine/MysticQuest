package org.hyzionstudios.mysticquests.state;

import java.util.Map;
import java.util.Set;

/**
 * An immutable point-in-time copy of one owner's state, handed to storage by the write queue.
 *
 * <p>Snapshotting on the mutating thread rather than the writer thread is what makes the write
 * asynchronous safely: the writer never touches live collections, so state can keep changing (or be
 * cleared on disconnect) while an earlier write is still in flight.
 *
 * <p>An {@link #isEmpty() empty} snapshot means "this owner has no state" and instructs storage to
 * delete the owner's rows rather than write them.
 */
public record StateSnapshot(
        StateKey key,
        Set<String> tags,
        Map<String, String> variables,
        Map<String, String> metadata) {

    public boolean isEmpty() {
        return tags.isEmpty() && variables.isEmpty() && metadata.isEmpty();
    }

    public StateScope scope() {
        return key.scope();
    }

    public String owner() {
        return key.owner();
    }
}
