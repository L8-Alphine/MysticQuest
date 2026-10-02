package org.hyzionstudios.mysticquests.state;

import javax.annotation.Nullable;
import java.util.Collections;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Live tag, variable, and metadata state for one owner.
 *
 * <p>Every collection is concurrent, so reads never block writes and the store needs no lock of its
 * own. Readers get unmodifiable <em>views</em> rather than copies: the old service copied every set
 * and map on each read, which made a condition check on a hot quest allocate proportionally to how
 * much state the owner had.
 */
public final class StateEntry {
    private final Set<String> tags = ConcurrentHashMap.newKeySet();
    private final Map<String, String> variables = new ConcurrentHashMap<>();
    private final Map<String, String> metadata = new ConcurrentHashMap<>();

    /** @return true when the tag was not already present */
    public boolean addTag(String tag) {
        return tags.add(tag);
    }

    /** @return true when the tag was present and has been removed */
    public boolean removeTag(String tag) {
        return tags.remove(tag);
    }

    public boolean hasTag(String tag) {
        return tags.contains(tag);
    }

    /** A live unmodifiable view of the tags — reflects later mutations, and allocates nothing. */
    public Set<String> tags() {
        return Collections.unmodifiableSet(tags);
    }

    @Nullable
    public String variable(String key) {
        return variables.get(key);
    }

    /** @return the previous value, or null when the variable was unset */
    @Nullable
    public String setVariable(String key, String value) {
        return variables.put(key, value);
    }

    /** @return the removed value, or null when the variable was already unset */
    @Nullable
    public String removeVariable(String key) {
        return variables.remove(key);
    }

    /**
     * Adds {@code delta} to a variable parsed as a long, atomically with respect to other
     * increments of the same key.
     *
     * @return the value after the increment
     */
    public long incrementVariable(String key, long delta) {
        String updated = variables.compute(key, (ignored, current) -> Long.toString(parseLong(current) + delta));
        return parseLong(updated);
    }

    /** A live unmodifiable view of the variables. */
    public Map<String, String> variables() {
        return Collections.unmodifiableMap(variables);
    }

    public void putMetadata(Map<String, String> values) {
        metadata.putAll(values);
    }

    /** A live unmodifiable view of the metadata. */
    public Map<String, String> metadata() {
        return Collections.unmodifiableMap(metadata);
    }

    public boolean isEmpty() {
        return tags.isEmpty() && variables.isEmpty() && metadata.isEmpty();
    }

    public void clear() {
        tags.clear();
        variables.clear();
        metadata.clear();
    }

    /** Deep, immutable copy for handing to the write queue without racing further mutation. */
    public StateSnapshot snapshot(StateKey key) {
        return new StateSnapshot(key, Set.copyOf(tags), Map.copyOf(variables), Map.copyOf(metadata));
    }

    /** Parses a stored variable as a long, treating unset and non-numeric values as zero. */
    public static long parseLong(@Nullable String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }
}
