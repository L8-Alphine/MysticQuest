package org.hyzionstudios.mysticquests.narrative.value;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A complete variable type: the base {@link ValueType} plus, for containers, the element type and,
 * for enums, the allowed values.
 *
 * <p>Written in content as a short string — {@code "integer"}, {@code "list<string>"},
 * {@code "map<long>"} — with enum values given separately, because a list of identifiers inside the
 * type string would need its own escaping rules.
 */
public record TypeSpec(ValueType type, @Nullable TypeSpec element, List<String> enumValues) {
    public TypeSpec {
        Objects.requireNonNull(type, "type");
        enumValues = enumValues == null ? List.of() : List.copyOf(enumValues);
        if (type.container() && element == null) {
            throw new IllegalArgumentException(type.id() + " needs an element type, e.g. '" + type.id() + "<string>'.");
        }
        if (!type.container() && element != null) {
            throw new IllegalArgumentException(type.id() + " does not take an element type.");
        }
        if (type == ValueType.ENUM && enumValues.isEmpty()) {
            throw new IllegalArgumentException("An enum variable must list its allowed values.");
        }
    }

    public static TypeSpec of(ValueType type) {
        return new TypeSpec(type, null, List.of());
    }

    public static TypeSpec listOf(TypeSpec element) {
        return new TypeSpec(ValueType.LIST, element, List.of());
    }

    public static TypeSpec setOf(TypeSpec element) {
        return new TypeSpec(ValueType.SET, element, List.of());
    }

    public static TypeSpec mapOf(TypeSpec element) {
        return new TypeSpec(ValueType.MAP, element, List.of());
    }

    public static TypeSpec enumOf(List<String> values) {
        return new TypeSpec(ValueType.ENUM, null, values);
    }

    /**
     * Parses {@code "integer"}, {@code "list<string>"}, {@code "map<list<long>>"} and so on.
     *
     * @param enumValues the allowed values when the type is {@code enum}; ignored otherwise
     * @throws IllegalArgumentException naming the part of the text that could not be read
     */
    public static TypeSpec parse(String text, @Nullable List<String> enumValues) {
        if (text == null || text.isBlank()) {
            throw new IllegalArgumentException("Type is blank.");
        }
        String trimmed = text.trim();
        int open = trimmed.indexOf('<');
        if (open < 0) {
            ValueType type = ValueType.parse(trimmed);
            if (type == null) {
                throw new IllegalArgumentException("Unknown type '" + trimmed + "'.");
            }
            return type == ValueType.ENUM ? enumOf(enumValues == null ? List.of() : enumValues) : of(type);
        }
        if (!trimmed.endsWith(">")) {
            throw new IllegalArgumentException("Type '" + trimmed + "' has an unclosed '<'.");
        }
        ValueType container = ValueType.parse(trimmed.substring(0, open));
        if (container == null || !container.container()) {
            throw new IllegalArgumentException("Only list, set and map take an element type: '" + trimmed + "'.");
        }
        TypeSpec element = parse(trimmed.substring(open + 1, trimmed.length() - 1), enumValues);
        return new TypeSpec(container, element, List.of());
    }

    @Override
    public String toString() {
        return element == null ? type.id() : type.id() + "<" + element + ">";
    }
}
