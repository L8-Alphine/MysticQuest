package org.hyzionstudios.mysticquests.narrative.value;

import org.hyzionstudios.mysticquests.narrative.value.QuestValue.BoolValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DoubleValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DurationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EnumValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.ListValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LocationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LongValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.MapValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.SetValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.StringValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.TimestampValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.UuidValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.annotation.Nullable;
import java.time.Duration;
import java.time.Instant;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Converts between {@link QuestValue}s and their JSON and text forms, and coerces raw input onto a
 * declared {@link TypeSpec}.
 *
 * <p>Coercion is strict about meaning and lenient about spelling. {@code "12"} is an acceptable
 * integer, because v1 stored every variable as text and migrated values arrive that way; {@code 4.5}
 * is not, because truncating it would silently change the story. Anything refused comes back as a
 * {@link Coercion} naming the expected type and what was found.
 */
public final class ValueCodec {
    private static final JsonNodeFactory NODES = JsonNodeFactory.instance;
    private static final ObjectMapper JSON = new ObjectMapper();
    private static final Pattern SHORT_DURATION = Pattern.compile("(\\d+)\\s*(ms|s|m|h|d)");

    private ValueCodec() {
    }

    // --- Coercion ---

    /** Coerces a JSON value onto {@code spec}. A missing or null node is refused. */
    public static Coercion coerce(TypeSpec spec, @Nullable JsonNode raw) {
        if (raw == null || raw.isNull() || raw.isMissingNode()) {
            return Coercion.rejected("expected " + spec + " but no value was given");
        }
        return switch (spec.type()) {
            case BOOLEAN -> bool(raw);
            case INTEGER -> integer(raw);
            case LONG -> longValue(raw);
            case DOUBLE -> doubleValue(raw);
            case STRING -> raw.isValueNode()
                    ? Coercion.ok(new StringValue(raw.asText()))
                    : refuse(spec, raw);
            case UUID -> uuid(raw);
            case DURATION -> duration(raw);
            case TIMESTAMP -> timestamp(raw);
            case LOCATION -> location(raw);
            case ENTITY_REFERENCE -> entity(raw);
            case LIST, SET -> collection(spec, raw);
            case MAP -> map(spec, raw);
            case ENUM -> enumeration(spec, raw);
        };
    }

    /** Coerces text — a command argument or a v1 string variable — onto {@code spec}. */
    public static Coercion coerceText(TypeSpec spec, @Nullable String raw) {
        if (raw == null) {
            return Coercion.rejected("expected " + spec + " but no value was given");
        }
        if (spec.type().container() || spec.type() == ValueType.LOCATION && raw.trim().startsWith("{")) {
            try {
                return coerce(spec, JSON.readTree(raw));
            } catch (java.io.IOException invalid) {
                return Coercion.rejected("expected " + spec + " as JSON but could not parse '" + raw + "'");
            }
        }
        return coerce(spec, TextNode.valueOf(raw));
    }

    private static Coercion bool(JsonNode raw) {
        if (raw.isBoolean()) {
            return Coercion.ok(new BoolValue(raw.booleanValue()));
        }
        if (raw.isTextual()) {
            String text = raw.textValue().trim().toLowerCase(Locale.ROOT);
            if (text.equals("true") || text.equals("false")) {
                return Coercion.ok(new BoolValue(text.equals("true")));
            }
        }
        return refuse(TypeSpec.of(ValueType.BOOLEAN), raw);
    }

    private static Coercion integer(JsonNode raw) {
        if (raw.isIntegralNumber()) {
            return raw.canConvertToInt()
                    ? Coercion.ok(new IntValue(raw.intValue()))
                    : Coercion.rejected("expected integer but " + raw.asText() + " is out of range");
        }
        if (raw.isTextual()) {
            try {
                return Coercion.ok(new IntValue(Integer.parseInt(raw.textValue().trim())));
            } catch (NumberFormatException ignored) {
                // Falls through to the refusal below.
            }
        }
        return refuse(TypeSpec.of(ValueType.INTEGER), raw);
    }

    private static Coercion longValue(JsonNode raw) {
        if (raw.isIntegralNumber()) {
            return raw.canConvertToLong()
                    ? Coercion.ok(new LongValue(raw.longValue()))
                    : Coercion.rejected("expected long but " + raw.asText() + " is out of range");
        }
        if (raw.isTextual()) {
            try {
                return Coercion.ok(new LongValue(Long.parseLong(raw.textValue().trim())));
            } catch (NumberFormatException ignored) {
                // Falls through to the refusal below.
            }
        }
        return refuse(TypeSpec.of(ValueType.LONG), raw);
    }

    private static Coercion doubleValue(JsonNode raw) {
        double value;
        if (raw.isNumber()) {
            value = raw.doubleValue();
        } else if (raw.isTextual()) {
            try {
                value = Double.parseDouble(raw.textValue().trim());
            } catch (NumberFormatException ignored) {
                return refuse(TypeSpec.of(ValueType.DOUBLE), raw);
            }
        } else {
            return refuse(TypeSpec.of(ValueType.DOUBLE), raw);
        }
        if (Double.isNaN(value) || Double.isInfinite(value)) {
            return Coercion.rejected("expected a finite double but found " + raw.asText());
        }
        return Coercion.ok(new DoubleValue(value));
    }

    private static Coercion uuid(JsonNode raw) {
        if (raw.isTextual()) {
            try {
                return Coercion.ok(new UuidValue(UUID.fromString(raw.textValue().trim())));
            } catch (IllegalArgumentException ignored) {
                // Falls through to the refusal below.
            }
        }
        return refuse(TypeSpec.of(ValueType.UUID), raw);
    }

    /**
     * ISO-8601 ({@code PT15S}), shorthand ({@code 15s}, {@code 500ms}, {@code 2m}, {@code 1h},
     * {@code 1d}), or a whole number of milliseconds.
     */
    private static Coercion duration(JsonNode raw) {
        Duration value = null;
        if (raw.isIntegralNumber() && raw.canConvertToLong()) {
            value = Duration.ofMillis(raw.longValue());
        } else if (raw.isTextual()) {
            value = parseDuration(raw.textValue());
        }
        if (value == null) {
            return refuse(TypeSpec.of(ValueType.DURATION), raw);
        }
        if (value.isNegative()) {
            return Coercion.rejected("a duration cannot be negative: " + raw.asText());
        }
        return Coercion.ok(new DurationValue(value));
    }

    /** Parses the same duration forms as {@link #coerce}; null when the text is none of them. */
    @Nullable
    public static Duration parseDuration(@Nullable String text) {
        if (text == null || text.isBlank()) {
            return null;
        }
        String trimmed = text.trim();
        Matcher shorthand = SHORT_DURATION.matcher(trimmed.toLowerCase(Locale.ROOT));
        if (shorthand.matches()) {
            long amount = Long.parseLong(shorthand.group(1));
            return switch (shorthand.group(2)) {
                case "ms" -> Duration.ofMillis(amount);
                case "s" -> Duration.ofSeconds(amount);
                case "m" -> Duration.ofMinutes(amount);
                case "h" -> Duration.ofHours(amount);
                default -> Duration.ofDays(amount);
            };
        }
        try {
            return Duration.parse(trimmed);
        } catch (DateTimeParseException ignored) {
            return null;
        }
    }

    private static Coercion timestamp(JsonNode raw) {
        if (raw.isIntegralNumber() && raw.canConvertToLong()) {
            return Coercion.ok(new TimestampValue(Instant.ofEpochMilli(raw.longValue())));
        }
        if (raw.isTextual()) {
            try {
                return Coercion.ok(new TimestampValue(Instant.parse(raw.textValue().trim())));
            } catch (DateTimeParseException ignored) {
                // Falls through to the refusal below.
            }
        }
        return refuse(TypeSpec.of(ValueType.TIMESTAMP), raw);
    }

    /** An object {@code {world, x, y, z}}, or the v1 block-key text form {@code world:x:y:z}. */
    private static Coercion location(JsonNode raw) {
        if (raw.isObject()) {
            JsonNode world = raw.get("world");
            JsonNode x = raw.get("x");
            JsonNode y = raw.get("y");
            JsonNode z = raw.get("z");
            if (world != null && world.isTextual() && isNumber(x) && isNumber(y) && isNumber(z)) {
                return Coercion.ok(new LocationValue(world.textValue(), x.doubleValue(), y.doubleValue(), z.doubleValue()));
            }
        } else if (raw.isTextual()) {
            String[] parts = raw.textValue().trim().split(":");
            if (parts.length == 4) {
                try {
                    return Coercion.ok(new LocationValue(
                            parts[0],
                            Double.parseDouble(parts[1]),
                            Double.parseDouble(parts[2]),
                            Double.parseDouble(parts[3])));
                } catch (NumberFormatException ignored) {
                    // Falls through to the refusal below.
                }
            }
        }
        return Coercion.rejected("expected location as {world,x,y,z} or 'world:x:y:z' but found " + describe(raw));
    }

    private static boolean isNumber(@Nullable JsonNode node) {
        return node != null && node.isNumber();
    }

    /** {@code kind:id}, an object {@code {kind, id}}, or a bare UUID meaning {@code uuid:<id>}. */
    private static Coercion entity(JsonNode raw) {
        if (raw.isObject()) {
            JsonNode kind = raw.get("kind");
            JsonNode id = raw.get("id");
            if (kind != null && kind.isTextual() && id != null && id.isTextual()
                    && !kind.textValue().isBlank() && !id.textValue().isBlank()) {
                return Coercion.ok(new EntityRefValue(kind.textValue().trim().toLowerCase(Locale.ROOT), id.textValue().trim()));
            }
        } else if (raw.isTextual()) {
            String text = raw.textValue().trim();
            try {
                return Coercion.ok(new EntityRefValue("uuid", UUID.fromString(text).toString()));
            } catch (IllegalArgumentException notAUuid) {
                int colon = text.indexOf(':');
                if (colon > 0 && colon < text.length() - 1) {
                    return Coercion.ok(new EntityRefValue(
                            text.substring(0, colon).toLowerCase(Locale.ROOT), text.substring(colon + 1)));
                }
            }
        }
        return Coercion.rejected("expected entity reference as 'kind:id' or a UUID but found " + describe(raw));
    }

    private static Coercion collection(TypeSpec spec, JsonNode raw) {
        if (!raw.isArray()) {
            return refuse(spec, raw);
        }
        List<QuestValue> values = new ArrayList<>(raw.size());
        int index = 0;
        for (JsonNode element : raw) {
            Coercion coerced = coerce(spec.element(), element);
            if (!coerced.accepted()) {
                return Coercion.rejected("element " + index + " of " + spec + ": " + coerced.problem());
            }
            values.add(coerced.value());
            index++;
        }
        return Coercion.ok(spec.type() == ValueType.SET
                ? new SetValue(new LinkedHashSet<>(values))
                : new ListValue(values));
    }

    private static Coercion map(TypeSpec spec, JsonNode raw) {
        if (!raw.isObject()) {
            return refuse(spec, raw);
        }
        Map<String, QuestValue> values = new LinkedHashMap<>();
        for (Map.Entry<String, JsonNode> entry : raw.properties()) {
            Coercion coerced = coerce(spec.element(), entry.getValue());
            if (!coerced.accepted()) {
                return Coercion.rejected("key '" + entry.getKey() + "' of " + spec + ": " + coerced.problem());
            }
            values.put(entry.getKey(), coerced.value());
        }
        return Coercion.ok(new MapValue(values));
    }

    private static Coercion enumeration(TypeSpec spec, JsonNode raw) {
        if (raw.isTextual() && spec.enumValues().contains(raw.textValue())) {
            return Coercion.ok(new EnumValue(raw.textValue()));
        }
        return Coercion.rejected("expected one of " + spec.enumValues() + " but found " + describe(raw));
    }

    private static Coercion refuse(TypeSpec spec, JsonNode raw) {
        return Coercion.rejected("expected " + spec + " but found " + describe(raw));
    }

    private static String describe(JsonNode raw) {
        if (raw.isTextual()) {
            return "'" + raw.textValue() + "'";
        }
        if (raw.isContainerNode()) {
            return raw.isArray() ? "an array" : "an object";
        }
        return raw.asText();
    }

    /** Whether {@code value} satisfies {@code spec}, including element types and enum membership. */
    public static boolean conforms(TypeSpec spec, QuestValue value) {
        if (value.type() != spec.type()) {
            return false;
        }
        return switch (value) {
            case ListValue list -> list.values().stream().allMatch(element -> conforms(spec.element(), element));
            case SetValue set -> set.values().stream().allMatch(element -> conforms(spec.element(), element));
            case MapValue map -> map.values().values().stream().allMatch(element -> conforms(spec.element(), element));
            case EnumValue enumValue -> spec.enumValues().contains(enumValue.value());
            default -> true;
        };
    }

    // --- Encoding ---

    /** The JSON form persisted documents store; {@link #coerce} reads it back losslessly. */
    public static JsonNode toJson(QuestValue value) {
        return switch (value) {
            case BoolValue bool -> NODES.booleanNode(bool.value());
            case IntValue integer -> NODES.numberNode(integer.value());
            case LongValue longValue -> NODES.numberNode(longValue.value());
            case DoubleValue doubleValue -> NODES.numberNode(doubleValue.value());
            case StringValue string -> NODES.textNode(string.value());
            case UuidValue uuid -> NODES.textNode(uuid.value().toString());
            case DurationValue duration -> NODES.textNode(duration.value().toString());
            case TimestampValue timestamp -> NODES.textNode(timestamp.value().toString());
            case LocationValue location -> {
                ObjectNode node = NODES.objectNode();
                node.put("world", location.world());
                node.put("x", location.x());
                node.put("y", location.y());
                node.put("z", location.z());
                yield node;
            }
            case EntityRefValue entity -> NODES.textNode(entity.kind() + ":" + entity.id());
            case ListValue list -> array(list.values());
            case SetValue set -> array(set.values());
            case MapValue map -> {
                ObjectNode node = NODES.objectNode();
                map.values().forEach((key, element) -> node.set(key, toJson(element)));
                yield node;
            }
            case EnumValue enumValue -> NODES.textNode(enumValue.value());
        };
    }

    private static ArrayNode array(Iterable<QuestValue> values) {
        ArrayNode node = NODES.arrayNode();
        values.forEach(element -> node.add(toJson(element)));
        return node;
    }

    /** Human-readable text for placeholders, debug output and the v1 string mirror. */
    public static String toText(QuestValue value) {
        return switch (value) {
            case BoolValue bool -> Boolean.toString(bool.value());
            case IntValue integer -> Integer.toString(integer.value());
            case LongValue longValue -> Long.toString(longValue.value());
            case DoubleValue doubleValue -> Double.toString(doubleValue.value());
            case StringValue string -> string.value();
            case EnumValue enumValue -> enumValue.value();
            default -> {
                JsonNode json = toJson(value);
                yield json.isTextual() ? json.textValue() : json.toString();
            }
        };
    }

    /** A value of {@code spec}'s type with no content: zero, false, empty. Used for increments. */
    public static QuestValue zero(TypeSpec spec) {
        return switch (spec.type()) {
            case BOOLEAN -> new BoolValue(false);
            case INTEGER -> new IntValue(0);
            case LONG -> new LongValue(0L);
            case DOUBLE -> new DoubleValue(0d);
            case STRING -> new StringValue("");
            case DURATION -> new DurationValue(Duration.ZERO);
            case LIST -> new ListValue(List.of());
            case SET -> new SetValue(Set.of());
            case MAP -> new MapValue(Map.of());
            default -> throw new IllegalArgumentException(spec + " has no zero value.");
        };
    }
}
