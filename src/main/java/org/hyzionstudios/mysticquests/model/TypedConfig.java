package org.hyzionstudios.mysticquests.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Supplier;

public class TypedConfig {
    private String type;
    private final Map<String, JsonNode> data = new LinkedHashMap<>();

    public String type() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    @JsonAnySetter
    public void put(String key, JsonNode value) {
        data.put(key, value);
    }

    @JsonAnyGetter
    public Map<String, JsonNode> data() {
        return data;
    }

    public Optional<String> text(String key) {
        JsonNode node = data.get(key);
        return node == null || node.isNull() ? Optional.empty() : Optional.of(node.asText());
    }

    public String text(String key, String fallback) {
        return text(key).orElse(fallback);
    }

    public int integer(String key, int fallback) {
        JsonNode node = data.get(key);
        return node == null || !node.canConvertToInt() ? fallback : node.asInt();
    }

    public long longValue(String key, long fallback) {
        JsonNode node = data.get(key);
        return node == null || !node.canConvertToLong() ? fallback : node.asLong();
    }

    public boolean bool(String key, boolean fallback) {
        JsonNode node = data.get(key);
        return node == null || !node.isBoolean() ? fallback : node.asBoolean();
    }

    public BigDecimal decimal(String key, BigDecimal fallback) {
        JsonNode node = data.get(key);
        if (node == null || !node.isNumber()) {
            return fallback;
        }
        return node.decimalValue();
    }

    /**
     * Reads a nested array of typed entries, for example the {@code conditions} of an {@code and}
     * condition or the {@code events} of a {@code folder} event. A single object is accepted in
     * place of a one-element array so authors can write {@code "condition": {...}}.
     */
    public <T extends TypedConfig> List<T> children(String key, Supplier<T> factory) {
        JsonNode node = data.get(key);
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isObject()) {
            return List.of(child(node, factory));
        }
        if (!node.isArray()) {
            return List.of();
        }
        List<T> children = new ArrayList<>(node.size());
        for (JsonNode element : node) {
            if (element.isObject()) {
                children.add(child(element, factory));
            }
        }
        return List.copyOf(children);
    }

    private <T extends TypedConfig> T child(JsonNode node, Supplier<T> factory) {
        T value = factory.get();
        for (Map.Entry<String, JsonNode> field : node.properties()) {
            if (field.getKey().equals("type")) {
                value.setType(field.getValue().asText());
            } else {
                value.put(field.getKey(), field.getValue());
            }
        }
        return value;
    }
}
