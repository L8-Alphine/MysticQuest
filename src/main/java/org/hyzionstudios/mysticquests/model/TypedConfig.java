package org.hyzionstudios.mysticquests.model;

import com.fasterxml.jackson.annotation.JsonAnyGetter;
import com.fasterxml.jackson.annotation.JsonAnySetter;
import com.fasterxml.jackson.databind.JsonNode;

import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

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
}
