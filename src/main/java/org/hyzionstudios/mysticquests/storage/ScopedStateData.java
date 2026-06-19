package org.hyzionstudios.mysticquests.storage;

import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public final class ScopedStateData {
    private Map<String, ScopedEntry> player = new LinkedHashMap<>();
    private Map<String, ScopedEntry> global = new LinkedHashMap<>();
    private Map<String, ScopedEntry> entity = new LinkedHashMap<>();
    private Map<String, ScopedEntry> block = new LinkedHashMap<>();
    private Map<String, ScopedEntry> volume = new LinkedHashMap<>();

    public Map<String, ScopedEntry> player() {
        return player;
    }

    public void setPlayer(Map<String, ScopedEntry> player) {
        this.player = player == null ? new LinkedHashMap<>() : player;
    }

    public Map<String, ScopedEntry> global() {
        return global;
    }

    public void setGlobal(Map<String, ScopedEntry> global) {
        this.global = global == null ? new LinkedHashMap<>() : global;
    }

    public Map<String, ScopedEntry> entity() {
        return entity;
    }

    public void setEntity(Map<String, ScopedEntry> entity) {
        this.entity = entity == null ? new LinkedHashMap<>() : entity;
    }

    public Map<String, ScopedEntry> block() {
        return block;
    }

    public void setBlock(Map<String, ScopedEntry> block) {
        this.block = block == null ? new LinkedHashMap<>() : block;
    }

    public Map<String, ScopedEntry> volume() {
        return volume;
    }

    public void setVolume(Map<String, ScopedEntry> volume) {
        this.volume = volume == null ? new LinkedHashMap<>() : volume;
    }

    public Map<String, ScopedEntry> entries(String scope) {
        return switch (scope) {
            case "global" -> global;
            case "entity" -> entity;
            case "block" -> block;
            case "volume" -> volume;
            default -> player;
        };
    }

    public static final class ScopedEntry {
        private Set<String> tags = new LinkedHashSet<>();
        private Map<String, String> variables = new LinkedHashMap<>();
        private Map<String, String> metadata = new LinkedHashMap<>();

        public Set<String> tags() {
            return tags;
        }

        public void setTags(Set<String> tags) {
            this.tags = tags == null ? new LinkedHashSet<>() : tags;
        }

        public Map<String, String> variables() {
            return variables;
        }

        public void setVariables(Map<String, String> variables) {
            this.variables = variables == null ? new LinkedHashMap<>() : variables;
        }

        public Map<String, String> metadata() {
            return metadata;
        }

        public void setMetadata(Map<String, String> metadata) {
            this.metadata = metadata == null ? new LinkedHashMap<>() : metadata;
        }
    }
}
