package org.hyzionstudios.mysticquests.state;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.hyzionstudios.mysticquests.event.StateEvents.ChangeType;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * In-memory home for every tag and variable, across all five scopes.
 *
 * <p>Reads are lock-free and take a single map lookup; the previous implementation serialised every
 * read and write of every scope through one monitor. Writes mark the touched owner dirty and post a
 * change event, but never touch the disk themselves — {@link StateWriteQueue} drains dirty owners on
 * its own schedule. That split is what removes the write amplification: a tag change used to rewrite
 * every scoped row on the server, and now queues one small owner for a coalesced write.
 *
 * <p>Mutations return whether anything actually changed, so callers can skip dirtying and event
 * posting for no-op writes (re-adding a tag the owner already has is extremely common in quest
 * scripting).
 */
public final class MysticStateStore {
    private final Map<StateScope, ConcurrentHashMap<String, StateEntry>> scopes = new EnumMap<>(StateScope.class);
    private final Set<StateKey> dirty = ConcurrentHashMap.newKeySet();
    private final MysticQuestsEventBus eventBus;

    public MysticStateStore(MysticQuestsEventBus eventBus) {
        this.eventBus = eventBus;
        for (StateScope scope : StateScope.values()) {
            scopes.put(scope, new ConcurrentHashMap<>());
        }
    }

    // --- Tags ---

    public boolean hasTag(StateKey key, String tag) {
        StateEntry entry = find(key);
        return entry != null && entry.hasTag(tag);
    }

    public boolean addTag(StateKey key, String tag) {
        if (!create(key).addTag(tag)) {
            return false;
        }
        markDirty(key);
        eventBus.post(new StateEvents.TagChange(key.scope(), key.owner(), tag, ChangeType.ADD));
        return true;
    }

    public boolean removeTag(StateKey key, String tag) {
        StateEntry entry = find(key);
        if (entry == null || !entry.removeTag(tag)) {
            return false;
        }
        markDirty(key);
        eventBus.post(new StateEvents.TagChange(key.scope(), key.owner(), tag, ChangeType.REMOVE));
        return true;
    }

    /** A live unmodifiable view of an owner's tags; empty when the owner has no state. */
    public Set<String> tags(StateKey key) {
        StateEntry entry = find(key);
        return entry == null ? Set.of() : entry.tags();
    }

    // --- Variables ---

    @Nullable
    public String variable(StateKey key, String name) {
        StateEntry entry = find(key);
        return entry == null ? null : entry.variable(name);
    }

    public boolean setVariable(StateKey key, String name, String value) {
        // Variables are stored as strings; a null from an API caller becomes empty rather than
        // blowing up inside the concurrent map or the equality check below.
        String resolved = value == null ? "" : value;
        String previous = create(key).setVariable(name, resolved);
        if (resolved.equals(previous)) {
            return false;
        }
        markDirty(key);
        eventBus.post(new StateEvents.VariableChange(key.scope(), key.owner(), name, resolved, ChangeType.SET));
        return true;
    }

    public boolean removeVariable(StateKey key, String name) {
        StateEntry entry = find(key);
        if (entry == null || entry.removeVariable(name) == null) {
            return false;
        }
        markDirty(key);
        eventBus.post(new StateEvents.VariableChange(key.scope(), key.owner(), name, null, ChangeType.REMOVE));
        return true;
    }

    /** Atomically adds {@code delta} to a numeric variable and returns the value after the change. */
    public long incrementVariable(StateKey key, String name, long delta) {
        long updated = create(key).incrementVariable(name, delta);
        markDirty(key);
        String value = Long.toString(updated);
        eventBus.post(new StateEvents.VariableChange(key.scope(), key.owner(), name, value, ChangeType.SET));
        return updated;
    }

    /** A live unmodifiable view of an owner's variables; empty when the owner has no state. */
    public Map<String, String> variables(StateKey key) {
        StateEntry entry = find(key);
        return entry == null ? Map.of() : entry.variables();
    }

    // --- Metadata ---

    public void putMetadata(StateKey key, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        create(key).putMetadata(metadata);
        markDirty(key);
    }

    public Map<String, String> metadata(StateKey key) {
        StateEntry entry = find(key);
        return entry == null ? Map.of() : entry.metadata();
    }

    // --- Bulk ---

    /** Drops every tag, variable, and metadata value for one owner. */
    public boolean clearOwner(StateKey key) {
        StateEntry removed = scopes.get(key.scope()).remove(key.owner());
        if (removed == null) {
            return false;
        }
        markDirty(key);
        eventBus.post(new StateEvents.TagChange(key.scope(), key.owner(), null, ChangeType.CLEAR));
        eventBus.post(new StateEvents.VariableChange(key.scope(), key.owner(), null, null, ChangeType.CLEAR));
        return true;
    }

    /** Owner ids that currently hold state in a scope. Used by admin tooling, not hot paths. */
    public Set<String> owners(StateScope scope) {
        return Set.copyOf(scopes.get(scope).keySet());
    }

    /**
     * Installs state read from disk at startup, without marking it dirty — otherwise the first
     * flush after boot would pointlessly rewrite everything that was just read.
     */
    public void load(Collection<StateSnapshot> snapshots) {
        for (StateSnapshot snapshot : snapshots) {
            StateEntry entry = create(snapshot.key());
            for (String tag : snapshot.tags()) {
                entry.addTag(tag);
            }
            snapshot.variables().forEach(entry::setVariable);
            entry.putMetadata(snapshot.metadata());
        }
        dirty.clear();
    }

    /**
     * Removes and returns snapshots of every owner changed since the last drain. Snapshots of
     * owners that were cleared come back {@link StateSnapshot#isEmpty() empty}, which tells storage
     * to delete their rows.
     */
    public List<StateSnapshot> drainDirty() {
        if (dirty.isEmpty()) {
            return List.of();
        }
        List<StateSnapshot> drained = new ArrayList<>(dirty.size());
        // Iterate a copy: removing as we go leaves concurrent marks from other threads in place
        // rather than dropping a change that landed mid-drain.
        for (StateKey key : List.copyOf(dirty)) {
            dirty.remove(key);
            StateEntry entry = find(key);
            drained.add(entry == null
                    ? new StateSnapshot(key, Set.of(), Map.of(), Map.of())
                    : entry.snapshot(key));
        }
        return drained;
    }

    public boolean hasPendingChanges() {
        return !dirty.isEmpty();
    }

    /** Marks an owner for persistence. Public so services that mutate entries directly can report it. */
    public void markDirty(StateKey key) {
        dirty.add(key);
    }

    @Nullable
    private StateEntry find(StateKey key) {
        return scopes.get(key.scope()).get(key.owner());
    }

    private StateEntry create(StateKey key) {
        return scopes.get(key.scope()).computeIfAbsent(key.owner(), ignored -> new StateEntry());
    }
}
