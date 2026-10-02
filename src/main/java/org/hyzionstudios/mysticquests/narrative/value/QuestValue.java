package org.hyzionstudios.mysticquests.narrative.value;

import java.time.Duration;
import java.time.Instant;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * An immutable typed variable value.
 *
 * <p>Sealed so a {@code switch} over values is exhaustive: adding a type is a compile error at every
 * place that has to handle it, rather than a runtime fall-through to "treat it as a string".
 */
public sealed interface QuestValue {
    ValueType type();

    record BoolValue(boolean value) implements QuestValue {
        public ValueType type() {
            return ValueType.BOOLEAN;
        }
    }

    record IntValue(int value) implements QuestValue {
        public ValueType type() {
            return ValueType.INTEGER;
        }
    }

    record LongValue(long value) implements QuestValue {
        public ValueType type() {
            return ValueType.LONG;
        }
    }

    record DoubleValue(double value) implements QuestValue {
        public DoubleValue {
            if (Double.isNaN(value) || Double.isInfinite(value)) {
                throw new IllegalArgumentException("A double variable cannot hold " + value + ".");
            }
        }

        public ValueType type() {
            return ValueType.DOUBLE;
        }
    }

    record StringValue(String value) implements QuestValue {
        public StringValue {
            Objects.requireNonNull(value, "value");
        }

        public ValueType type() {
            return ValueType.STRING;
        }
    }

    record UuidValue(java.util.UUID value) implements QuestValue {
        public UuidValue {
            Objects.requireNonNull(value, "value");
        }

        public ValueType type() {
            return ValueType.UUID;
        }
    }

    record DurationValue(Duration value) implements QuestValue {
        public DurationValue {
            Objects.requireNonNull(value, "value");
            if (value.isNegative()) {
                throw new IllegalArgumentException("A duration cannot be negative.");
            }
        }

        public ValueType type() {
            return ValueType.DURATION;
        }
    }

    record TimestampValue(Instant value) implements QuestValue {
        public TimestampValue {
            Objects.requireNonNull(value, "value");
        }

        public ValueType type() {
            return ValueType.TIMESTAMP;
        }
    }

    /** A point in a named world. Block keys in v1 used the same {@code world:x:y:z} shape. */
    record LocationValue(String world, double x, double y, double z) implements QuestValue {
        public LocationValue {
            Objects.requireNonNull(world, "world");
        }

        public ValueType type() {
            return ValueType.LOCATION;
        }
    }

    /**
     * A reference to an entity by how it is identified, not by a live handle: {@code uuid} for an
     * entity UUID, {@code generation} for a MysticGeneration stable identity. Live refs do not
     * survive a restart, so they are never persisted.
     */
    record EntityRefValue(String kind, String id) implements QuestValue {
        public EntityRefValue {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(id, "id");
        }

        public ValueType type() {
            return ValueType.ENTITY_REFERENCE;
        }
    }

    record ListValue(List<QuestValue> values) implements QuestValue {
        public ListValue {
            values = List.copyOf(values);
        }

        public ValueType type() {
            return ValueType.LIST;
        }
    }

    /** Insertion-ordered, so persisted documents and debug output are stable between runs. */
    record SetValue(Set<QuestValue> values) implements QuestValue {
        public SetValue {
            values = Collections.unmodifiableSet(new LinkedHashSet<>(values));
        }

        public ValueType type() {
            return ValueType.SET;
        }
    }

    record MapValue(Map<String, QuestValue> values) implements QuestValue {
        public MapValue {
            values = Collections.unmodifiableMap(new LinkedHashMap<>(values));
        }

        public ValueType type() {
            return ValueType.MAP;
        }
    }

    record EnumValue(String value) implements QuestValue {
        public EnumValue {
            Objects.requireNonNull(value, "value");
        }

        public ValueType type() {
            return ValueType.ENUM;
        }
    }
}
