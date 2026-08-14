package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;
import org.hyzionstudios.mysticquests.storage.QuestStorage;
import org.hyzionstudios.mysticquests.storage.ScopedStateData;

import com.hypixel.hytale.logger.HytaleLogger;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

public final class ScopedStateService {
    public static final String GLOBAL_OWNER = "__global__";

    private final QuestStorage storage;
    private final HyExtrasBridge hyExtrasBridge;
    private final boolean migrateLegacyTags;
    private final boolean migrateLegacyVariables;
    private final HytaleLogger logger;
    private final ScopedStateData state;

    public ScopedStateService(
            QuestStorage storage,
            HyExtrasBridge hyExtrasBridge,
            boolean migrateLegacyTags,
            boolean migrateLegacyVariables,
            HytaleLogger logger) throws IOException {
        this.storage = storage;
        this.hyExtrasBridge = hyExtrasBridge;
        this.migrateLegacyTags = migrateLegacyTags;
        this.migrateLegacyVariables = migrateLegacyVariables;
        this.logger = logger;
        this.state = storage.loadScopedState();
    }

    public synchronized boolean hasTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        if (tag.isBlank()) {
            return false;
        }
        return entry(scope(config), owner(playerId, config, targetContext), false).tags().contains(tag);
    }

    public synchronized void addTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        if (tag.isBlank()) {
            return;
        }
        String scope = scope(config);
        String owner = owner(playerId, config, targetContext);
        if (owner.isBlank()) {
            return;
        }
        rememberMetadata(scope, owner, targetContext);
        entry(scope, owner, true).tags().add(tag);
        if (scope.equals("player")) {
            hyExtrasBridge.addTag(UUID.fromString(owner), tag);
        }
        save();
    }

    public synchronized void removeTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        if (tag.isBlank()) {
            return;
        }
        String scope = scope(config);
        String owner = owner(playerId, config, targetContext);
        if (owner.isBlank()) {
            return;
        }
        rememberMetadata(scope, owner, targetContext);
        entry(scope, owner, false).tags().remove(tag);
        if (scope.equals("player")) {
            hyExtrasBridge.removeTag(UUID.fromString(owner), tag);
        }
        save();
    }

    public synchronized Set<String> tags(String scope, String owner) {
        return Set.copyOf(entry(normalizeScope(scope), normalizeOwner(scope, owner), false).tags());
    }

    /** Direct administrative mutation used by the in-game state editor. */
    public synchronized boolean addTag(String scope, String owner, String tag) {
        String normalizedScope = normalizeScope(scope);
        String normalizedOwner = normalizeOwner(normalizedScope, owner);
        if (tag == null || tag.isBlank() || normalizedOwner.isBlank()) {
            return false;
        }
        boolean changed = entry(normalizedScope, normalizedOwner, true).tags().add(tag.trim());
        if (changed && normalizedScope.equals("player")) {
            hyExtrasBridge.addTag(UUID.fromString(normalizedOwner), tag.trim());
        }
        if (changed) {
            save();
        }
        return changed;
    }

    /** Direct administrative mutation used by the in-game state editor. */
    public synchronized boolean removeTag(String scope, String owner, String tag) {
        String normalizedScope = normalizeScope(scope);
        String normalizedOwner = normalizeOwner(normalizedScope, owner);
        if (tag == null || tag.isBlank() || normalizedOwner.isBlank()) {
            return false;
        }
        boolean changed = entry(normalizedScope, normalizedOwner, false).tags().remove(tag.trim());
        if (changed && normalizedScope.equals("player")) {
            hyExtrasBridge.removeTag(UUID.fromString(normalizedOwner), tag.trim());
        }
        if (changed) {
            save();
        }
        return changed;
    }

    public synchronized String variable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        return entry(scope(config), owner(playerId, config, targetContext), false)
                .variables()
                .get(config.text("key", config.text("name", "")));
    }

    public synchronized void setVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String key = config.text("key", config.text("name", ""));
        if (key.isBlank()) {
            return;
        }
        String scope = scope(config);
        String owner = owner(playerId, config, targetContext);
        if (owner.isBlank()) {
            return;
        }
        String value = config.text("value", "");
        rememberMetadata(scope, owner, targetContext);
        entry(scope, owner, true).variables().put(key, value);
        if (scope.equals("player")) {
            hyExtrasBridge.setVariable(UUID.fromString(owner), key, value);
        }
        save();
    }

    public synchronized void removeVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String key = config.text("key", config.text("name", ""));
        if (key.isBlank()) {
            return;
        }
        String scope = scope(config);
        String owner = owner(playerId, config, targetContext);
        if (owner.isBlank()) {
            return;
        }
        rememberMetadata(scope, owner, targetContext);
        entry(scope, owner, false).variables().remove(key);
        if (scope.equals("player")) {
            hyExtrasBridge.removeVariable(UUID.fromString(owner), key);
        }
        save();
    }

    public synchronized long incrementVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String key = config.text("key", config.text("name", ""));
        if (key.isBlank()) {
            return 0L;
        }
        String scope = scope(config);
        String owner = owner(playerId, config, targetContext);
        if (owner.isBlank()) {
            return 0L;
        }
        long amount = config.longValue("amount", 1L);
        rememberMetadata(scope, owner, targetContext);
        ScopedStateData.ScopedEntry entry = entry(scope, owner, true);
        long current = parseLong(entry.variables().get(key));
        long updated = current + amount;
        entry.variables().put(key, Long.toString(updated));
        if (scope.equals("player")) {
            hyExtrasBridge.setVariable(UUID.fromString(owner), key, Long.toString(updated));
        }
        save();
        return updated;
    }

    public synchronized Map<String, String> variables(String scope, String owner) {
        return Map.copyOf(entry(normalizeScope(scope), normalizeOwner(scope, owner), false).variables());
    }

    /** Direct administrative mutation used by the in-game state editor. */
    public synchronized boolean setVariable(String scope, String owner, String key, String value) {
        String normalizedScope = normalizeScope(scope);
        String normalizedOwner = normalizeOwner(normalizedScope, owner);
        if (key == null || key.isBlank() || normalizedOwner.isBlank()) {
            return false;
        }
        String normalizedKey = key.trim();
        String normalizedValue = value == null ? "" : value;
        String previous = entry(normalizedScope, normalizedOwner, true).variables().put(normalizedKey, normalizedValue);
        if (normalizedScope.equals("player")) {
            hyExtrasBridge.setVariable(UUID.fromString(normalizedOwner), normalizedKey, normalizedValue);
        }
        save();
        return !java.util.Objects.equals(previous, normalizedValue);
    }

    /** Direct administrative mutation used by the in-game state editor. */
    public synchronized boolean removeVariable(String scope, String owner, String key) {
        String normalizedScope = normalizeScope(scope);
        String normalizedOwner = normalizeOwner(normalizedScope, owner);
        if (key == null || key.isBlank() || normalizedOwner.isBlank()) {
            return false;
        }
        String normalizedKey = key.trim();
        boolean changed = entry(normalizedScope, normalizedOwner, false).variables().remove(normalizedKey) != null;
        if (changed && normalizedScope.equals("player")) {
            hyExtrasBridge.removeVariable(UUID.fromString(normalizedOwner), normalizedKey);
        }
        if (changed) {
            save();
        }
        return changed;
    }

    public synchronized void putMetadata(String scope, String owner, Map<String, String> metadata) {
        if (metadata == null || metadata.isEmpty()) {
            return;
        }
        entry(normalizeScope(scope), normalizeOwner(scope, owner), true).metadata().putAll(metadata);
        save();
    }

    public synchronized void migrateLegacyPlayer(PlayerQuestData data) {
        String owner = data.playerId().toString();
        ScopedStateData.ScopedEntry entry = entry("player", owner, true);
        if (migrateLegacyTags) {
            for (String tag : data.tags()) {
                entry.tags().add(tag);
                hyExtrasBridge.addTag(data.playerId(), tag);
            }
        }
        if (migrateLegacyVariables) {
            for (Map.Entry<String, String> variable : data.playerVariables().entrySet()) {
                entry.variables().putIfAbsent(variable.getKey(), variable.getValue());
                hyExtrasBridge.setVariable(data.playerId(), variable.getKey(), variable.getValue());
            }
        }
        save();
    }

    public synchronized ScopedStateData snapshot() {
        return state;
    }

    public boolean compare(UUID playerId, TypedConfig condition, QuestTargetContext targetContext) {
        String actual = variable(playerId, condition, targetContext);
        String expected = condition.text("value", "");
        String operator = condition.text("operator", condition.text("op", "eq")).toLowerCase();
        return switch (operator) {
            case "ne", "!=", "not" -> actual == null || !actual.equals(expected);
            case "gt", ">" -> parseLong(actual) > parseLong(expected);
            case "gte", ">=" -> parseLong(actual) >= parseLong(expected);
            case "lt", "<" -> parseLong(actual) < parseLong(expected);
            case "lte", "<=" -> parseLong(actual) <= parseLong(expected);
            case "exists" -> actual != null;
            default -> expected.equals(actual);
        };
    }

    public static String normalizeScope(String scope) {
        if (scope == null || scope.isBlank()) {
            return "player";
        }
        return switch (scope.toLowerCase()) {
            case "global", "server" -> "global";
            case "entity", "npc" -> "entity";
            case "block" -> "block";
            case "volume", "trigger", "triggerVolume" -> "volume";
            default -> "player";
        };
    }

    public static String normalizeOwner(String scope, String owner) {
        String normalizedScope = normalizeScope(scope);
        if (normalizedScope.equals("global")) {
            return GLOBAL_OWNER;
        }
        return owner == null || owner.isBlank() ? GLOBAL_OWNER : owner;
    }

    private String scope(TypedConfig config) {
        return switch (config.type()) {
            case "globalTag", "globalVariable" -> "global";
            case "entityTag", "entityVariable" -> "entity";
            case "blockTag", "blockVariable" -> "block";
            case "volumeTag", "volumeVariable" -> "volume";
            default -> normalizeScope(config.text("scope", "player"));
        };
    }

    private String owner(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String scope = scope(config);
        String explicit = config.text("target", "");
        if (!explicit.isBlank()) {
            return normalizeOwner(scope, explicit);
        }
        return switch (scope) {
            case "global" -> GLOBAL_OWNER;
            case "entity" -> targetContext == null ? "" : normalizeOwner(scope, targetContext.entityId());
            case "block" -> targetContext == null ? "" : normalizeOwner(scope, targetContext.blockId());
            case "volume" -> targetContext == null ? "" : normalizeOwner(scope, targetContext.volumeKey());
            default -> playerId.toString();
        };
    }

    private ScopedStateData.ScopedEntry entry(String scope, String owner, boolean create) {
        Map<String, ScopedStateData.ScopedEntry> entries = state.entries(normalizeScope(scope));
        if (create) {
            return entries.computeIfAbsent(owner, ignored -> new ScopedStateData.ScopedEntry());
        }
        return entries.getOrDefault(owner, new ScopedStateData.ScopedEntry());
    }

    private void rememberMetadata(String scope, String owner, QuestTargetContext targetContext) {
        if (targetContext == null) {
            return;
        }
        if (scope.equals("entity") && owner != null && owner.equals(targetContext.entityId())) {
            entry(scope, owner, true).metadata().putAll(targetContext.entityMetadata());
        } else if (scope.equals("block") && owner != null && owner.equals(targetContext.blockId())) {
            entry(scope, owner, true).metadata().putAll(targetContext.blockMetadata());
        } else if (scope.equals("volume") && owner != null && owner.equals(targetContext.volumeKey())) {
            entry(scope, owner, true).metadata().putAll(targetContext.volumeMetadata());
        }
    }

    private long parseLong(String value) {
        if (value == null || value.isBlank()) {
            return 0L;
        }
        try {
            return Long.parseLong(value);
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    private void save() {
        try {
            storage.saveScopedState(state);
        } catch (IOException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to save MysticQuests scoped state.");
        }
    }
}
