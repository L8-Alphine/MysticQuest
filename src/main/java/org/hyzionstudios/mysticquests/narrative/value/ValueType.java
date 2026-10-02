package org.hyzionstudios.mysticquests.narrative.value;

import javax.annotation.Nullable;
import java.util.Locale;

/**
 * The value types a narrative variable can be declared with (§4.2 of the 2.0 specification).
 *
 * <p>v1 stored every variable as a string and parsed it at each use, so {@code "4"}, {@code "four"}
 * and {@code "4.0"} were all accepted on write and silently treated as zero or as text on read. A
 * declared type lets a bad write be rejected where it happens, with the variable's name in the error.
 */
public enum ValueType {
    BOOLEAN("boolean"),
    INTEGER("integer"),
    LONG("long"),
    DOUBLE("double"),
    STRING("string"),
    UUID("uuid"),
    DURATION("duration"),
    TIMESTAMP("timestamp"),
    LOCATION("location"),
    ENTITY_REFERENCE("entity"),
    LIST("list"),
    SET("set"),
    MAP("map"),
    ENUM("enum");

    private final String id;

    ValueType(String id) {
        this.id = id;
    }

    /** The lower-case name used in content and persisted documents. */
    public String id() {
        return id;
    }

    /** Whether values of this type have an order, so {@code <} and {@code >} mean something. */
    public boolean ordered() {
        return switch (this) {
            case INTEGER, LONG, DOUBLE, DURATION, TIMESTAMP -> true;
            default -> false;
        };
    }

    public boolean numeric() {
        return this == INTEGER || this == LONG || this == DOUBLE;
    }

    /** Whether this type holds other values, and so needs an element type. */
    public boolean container() {
        return this == LIST || this == SET || this == MAP;
    }

    /** Parses a type name, accepting the common aliases authors reach for. Null when unknown. */
    @Nullable
    public static ValueType parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "boolean", "bool" -> BOOLEAN;
            case "integer", "int" -> INTEGER;
            case "long" -> LONG;
            case "double", "number", "decimal" -> DOUBLE;
            case "string", "text" -> STRING;
            case "uuid" -> UUID;
            case "duration" -> DURATION;
            case "timestamp", "instant" -> TIMESTAMP;
            case "location" -> LOCATION;
            case "entity", "entity_reference", "entityreference" -> ENTITY_REFERENCE;
            case "list" -> LIST;
            case "set" -> SET;
            case "map" -> MAP;
            case "enum" -> ENUM;
            default -> null;
        };
    }
}
