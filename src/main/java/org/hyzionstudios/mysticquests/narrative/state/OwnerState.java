package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.UnaryOperator;

/**
 * The tags, variables and logical trigger overrides of one owner.
 *
 * <p>The same container backs every scope, including a session's own state, which is embedded in the
 * session document. That way a session carries its state with it on transfer, and one serialiser
 * covers both.
 *
 * <p>Collections are concurrent and reads are lock-free. Each change calls the host's listener, which
 * is how the store, or the session that embeds this, learns it has something to write.
 */
public final class OwnerState {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;

    private final Map<NamespacedId, TagRecord> tags = new ConcurrentHashMap<>();
    private final Map<NamespacedId, QuestValue> variables = new ConcurrentHashMap<>();
    private final Map<String, Boolean> triggerOverrides = new ConcurrentHashMap<>();
    private volatile Runnable changeListener = () -> {
    };

    public void onChange(@Nullable Runnable listener) {
        this.changeListener = listener == null ? () -> {
        } : listener;
    }

    // --- Tags ---

    /** The live record, or empty when absent or expired at {@code now}. */
    public Optional<TagRecord> tag(NamespacedId id, Instant now) {
        TagRecord record = tags.get(id);
        return record == null || record.expired(now) ? Optional.empty() : Optional.of(record);
    }

    /**
     * Adds or refreshes a tag. Re-adding a tag that is already live keeps its original
     * {@code addedAt} but takes the new expiry, so "extend this buff" works without a remove first.
     *
     * @return true when the tag was not live before
     */
    public boolean putTag(TagRecord record, Instant now) {
        TagRecord previous = tags.get(record.id());
        boolean wasLive = previous != null && !previous.expired(now);
        TagRecord stored = wasLive
                ? new TagRecord(record.id(), previous.addedAt(), record.expiresAt(), previous.source())
                : record;
        if (stored.equals(previous)) {
            return false;
        }
        tags.put(record.id(), stored);
        changeListener.run();
        return !wasLive;
    }

    /** @return true when a live tag was removed */
    public boolean removeTag(NamespacedId id, Instant now) {
        TagRecord removed = tags.remove(id);
        if (removed == null) {
            return false;
        }
        changeListener.run();
        return !removed.expired(now);
    }

    /** Live tags at {@code now}, in id order. */
    public List<TagRecord> liveTags(Instant now) {
        List<TagRecord> live = new ArrayList<>();
        for (TagRecord record : tags.values()) {
            if (!record.expired(now)) {
                live.add(record);
            }
        }
        live.sort((left, right) -> left.id().compareTo(right.id()));
        return live;
    }

    /** Drops expired tags; returns how many went. Expired tags are already invisible to reads. */
    public int purgeExpired(Instant now) {
        int before = tags.size();
        tags.values().removeIf(record -> record.expired(now));
        int purged = before - tags.size();
        if (purged > 0) {
            changeListener.run();
        }
        return purged;
    }

    // --- Variables ---

    @Nullable
    public QuestValue variable(NamespacedId id) {
        return variables.get(id);
    }

    /** @return true when the stored value changed */
    public boolean putVariable(NamespacedId id, QuestValue value) {
        QuestValue previous = variables.put(id, value);
        if (value.equals(previous)) {
            return false;
        }
        changeListener.run();
        return true;
    }

    /** @return true when a value was removed */
    public boolean removeVariable(NamespacedId id) {
        if (variables.remove(id) == null) {
            return false;
        }
        changeListener.run();
        return true;
    }

    /**
     * Atomically replaces a variable with a function of its current value. Used for increments, so
     * two increments racing on different threads cannot lose one. The function may return null to
     * leave the variable unset.
     */
    @Nullable
    public QuestValue computeVariable(NamespacedId id, UnaryOperator<QuestValue> update) {
        QuestValue[] previous = new QuestValue[1];
        QuestValue updated = variables.compute(id, (ignored, current) -> {
            previous[0] = current;
            return update.apply(current);
        });
        if (!Objects.equals(updated, previous[0])) {
            changeListener.run();
        }
        return updated;
    }

    public Map<NamespacedId, QuestValue> variables() {
        return Collections.unmodifiableMap(new TreeMap<>(variables));
    }

    // --- Logical trigger overrides ---

    /** The explicit enable or disable this owner holds for a volume, or null when it holds none. */
    @Nullable
    public Boolean triggerOverride(String volumeKey) {
        return triggerOverrides.get(volumeKey);
    }

    /** @param enabled the override to hold, or null to clear it */
    public boolean setTriggerOverride(String volumeKey, @Nullable Boolean enabled) {
        Boolean previous = enabled == null
                ? triggerOverrides.remove(volumeKey)
                : triggerOverrides.put(volumeKey, enabled);
        if (Objects.equals(previous, enabled)) {
            return false;
        }
        changeListener.run();
        return true;
    }

    public Map<String, Boolean> triggerOverrides() {
        return Collections.unmodifiableMap(new TreeMap<>(triggerOverrides));
    }

    // --- Lifecycle ---

    public boolean isEmpty() {
        return tags.isEmpty() && variables.isEmpty() && triggerOverrides.isEmpty();
    }

    /** Replaces everything with {@code other}'s contents; used by checkpoint rollback. */
    public void replaceWith(OwnerState other) {
        tags.clear();
        tags.putAll(other.tags);
        variables.clear();
        variables.putAll(other.variables);
        triggerOverrides.clear();
        triggerOverrides.putAll(other.triggerOverrides);
        changeListener.run();
    }

    public OwnerState copy() {
        OwnerState copy = new OwnerState();
        copy.tags.putAll(tags);
        copy.variables.putAll(variables);
        copy.triggerOverrides.putAll(triggerOverrides);
        return copy;
    }

    // --- Serialisation ---

    /**
     * The persisted form. Variables are stored with their type, so a document can be read back
     * without the schema. That matters when a later release changes or removes the declaration: the
     * stored value is still decoded exactly, and the variable service decides what to do about the
     * mismatch.
     */
    public ObjectNode toJson() {
        ObjectNode node = NODES.objectNode();
        ArrayNode tagArray = node.putArray("tags");
        for (TagRecord record : new TreeMap<>(tags).values()) {
            ObjectNode tag = tagArray.addObject();
            tag.put("id", record.id().toString());
            tag.put("addedAt", record.addedAt().toString());
            if (record.expiresAt() != null) {
                tag.put("expiresAt", record.expiresAt().toString());
            }
            if (!record.source().isEmpty()) {
                tag.put("source", record.source());
            }
        }
        ObjectNode variableNode = node.putObject("variables");
        new TreeMap<>(variables).forEach((id, value) -> {
            ObjectNode entry = variableNode.putObject(id.toString());
            entry.put("type", typeOf(value).toString());
            Set<String> enumValues = new LinkedHashSet<>();
            enumValuesOf(value, enumValues);
            if (!enumValues.isEmpty()) {
                ArrayNode values = entry.putArray("enumValues");
                enumValues.forEach(values::add);
            }
            entry.set("value", ValueCodec.toJson(value));
        });
        ObjectNode triggers = node.putObject("triggers");
        new TreeMap<>(triggerOverrides).forEach(triggers::put);
        return node;
    }

    /**
     * Reads the persisted form. Entries that cannot be decoded are skipped and reported to
     * {@code problems}, rather than failing the whole owner: one corrupt value should cost that value,
     * not a player's entire story state.
     */
    public static OwnerState fromJson(@Nullable JsonNode node, List<String> problems) {
        OwnerState state = new OwnerState();
        if (node == null || !node.isObject()) {
            return state;
        }
        for (JsonNode tag : node.path("tags")) {
            try {
                NamespacedId id = NamespacedId.parse(tag.path("id").asText(""));
                Instant addedAt = Instant.parse(tag.path("addedAt").asText());
                Instant expiresAt = tag.hasNonNull("expiresAt") ? Instant.parse(tag.get("expiresAt").asText()) : null;
                state.tags.put(id, new TagRecord(id, addedAt, expiresAt, tag.path("source").asText("")));
            } catch (RuntimeException invalid) {
                problems.add("skipped unreadable tag " + tag + ": " + invalid.getMessage());
            }
        }
        node.path("variables").properties().forEach(entry -> {
            try {
                NamespacedId id = NamespacedId.parse(entry.getKey());
                JsonNode stored = entry.getValue();
                List<String> enumValues = new ArrayList<>();
                stored.path("enumValues").forEach(value -> enumValues.add(value.asText()));
                TypeSpec type = TypeSpec.parse(stored.path("type").asText(""), enumValues);
                state.variables.put(id, ValueCodec.coerce(type, stored.get("value")).orThrow());
            } catch (RuntimeException invalid) {
                problems.add("skipped unreadable variable " + entry.getKey() + ": " + invalid.getMessage());
            }
        });
        node.path("triggers").properties().forEach(entry -> {
            if (entry.getValue().isBoolean()) {
                state.triggerOverrides.put(entry.getKey(), entry.getValue().booleanValue());
            } else {
                problems.add("skipped non-boolean trigger override " + entry.getKey());
            }
        });
        return state;
    }

    /**
     * The exact type of a stored value, including container element types, so it round-trips. An
     * empty container has no element to inspect and is recorded with string elements, which every
     * element type can be re-coerced from. Enum values are written separately, by
     * {@link #enumValuesOf}, because {@link TypeSpec#parse} takes them beside the type text.
     */
    private static TypeSpec typeOf(QuestValue value) {
        return switch (value) {
            case QuestValue.ListValue list -> TypeSpec.listOf(elementType(list.values()));
            case QuestValue.SetValue set -> TypeSpec.setOf(elementType(set.values()));
            case QuestValue.MapValue map -> TypeSpec.mapOf(elementType(map.values().values()));
            case QuestValue.EnumValue enumValue -> TypeSpec.enumOf(List.of(enumValue.value()));
            default -> TypeSpec.of(value.type());
        };
    }

    private static TypeSpec elementType(Collection<QuestValue> elements) {
        return elements.isEmpty() ? TypeSpec.of(ValueType.STRING) : typeOf(elements.iterator().next());
    }

    /** Every enum value anywhere inside {@code value}, so every element decodes against one list. */
    private static void enumValuesOf(QuestValue value, Set<String> into) {
        switch (value) {
            case QuestValue.EnumValue enumValue -> into.add(enumValue.value());
            case QuestValue.ListValue list -> list.values().forEach(element -> enumValuesOf(element, into));
            case QuestValue.SetValue set -> set.values().forEach(element -> enumValuesOf(element, into));
            case QuestValue.MapValue map -> map.values().values().forEach(element -> enumValuesOf(element, into));
            default -> {
            }
        }
    }
}
