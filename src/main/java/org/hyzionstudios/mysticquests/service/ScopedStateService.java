package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.state.StateEntry;
import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;

import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Translates authored quest content into state reads and writes.
 *
 * <p>This layer owns the mapping from a {@link TypedConfig} — a condition or event as written in a
 * quest package — onto a {@link StateKey}: which scope it addresses, and which owner within that
 * scope. The state itself lives in {@link MysticStateStore}, and persistence is handled off-thread by
 * the write queue, so nothing here touches the disk. Mutations used to serialise the whole server's
 * scoped state to storage before returning; now they mark one owner dirty and return.
 *
 * <p>When {@code hyExtrasExportPlayerState} is enabled, player-scope changes are mirrored into
 * HyExtras so a server running both mods sees one set of player tags. The mirror is one-way and
 * best-effort: MysticQuests is the source of truth and never reads back.
 */
public final class ScopedStateService {
    /** @deprecated use {@link StateScope#GLOBAL_OWNER}; kept so existing callers keep compiling. */
    @Deprecated
    public static final String GLOBAL_OWNER = StateScope.GLOBAL_OWNER;

    private final MysticStateStore store;
    private final HyExtrasBridge hyExtrasBridge;
    private final boolean migrateLegacyTags;
    private final boolean migrateLegacyVariables;

    public ScopedStateService(
            MysticStateStore store,
            HyExtrasBridge hyExtrasBridge,
            boolean migrateLegacyTags,
            boolean migrateLegacyVariables) {
        this.store = store;
        this.hyExtrasBridge = hyExtrasBridge;
        this.migrateLegacyTags = migrateLegacyTags;
        this.migrateLegacyVariables = migrateLegacyVariables;
    }

    /** The underlying store, for services that address state by {@link StateKey} directly. */
    public MysticStateStore store() {
        return store;
    }

    // --- Content-driven tag access ---

    public boolean hasTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        if (tag.isBlank()) {
            return false;
        }
        StateKey key = key(playerId, config, targetContext);
        return key != null && store.hasTag(key, tag);
    }

    public void addTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        StateKey key = key(playerId, config, targetContext);
        if (tag.isBlank() || key == null) {
            return;
        }
        rememberMetadata(key, targetContext);
        if (store.addTag(key, tag) && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.addTag(playerUuid(key), tag);
        }
    }

    public void removeTag(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String tag = config.text("tag", "");
        StateKey key = key(playerId, config, targetContext);
        if (tag.isBlank() || key == null) {
            return;
        }
        rememberMetadata(key, targetContext);
        if (store.removeTag(key, tag) && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.removeTag(playerUuid(key), tag);
        }
    }

    // --- Content-driven variable access ---

    public String variable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        StateKey key = key(playerId, config, targetContext);
        return key == null ? null : store.variable(key, variableName(config));
    }

    public void setVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String name = variableName(config);
        StateKey key = key(playerId, config, targetContext);
        if (name.isBlank() || key == null) {
            return;
        }
        String value = config.text("value", "");
        rememberMetadata(key, targetContext);
        if (store.setVariable(key, name, value) && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.setVariable(playerUuid(key), name, value);
        }
    }

    public void removeVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String name = variableName(config);
        StateKey key = key(playerId, config, targetContext);
        if (name.isBlank() || key == null) {
            return;
        }
        rememberMetadata(key, targetContext);
        if (store.removeVariable(key, name) && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.removeVariable(playerUuid(key), name);
        }
    }

    public long incrementVariable(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        String name = variableName(config);
        StateKey key = key(playerId, config, targetContext);
        if (name.isBlank() || key == null) {
            return 0L;
        }
        rememberMetadata(key, targetContext);
        long updated = store.incrementVariable(key, name, config.longValue("amount", 1L));
        if (key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.setVariable(playerUuid(key), name, Long.toString(updated));
        }
        return updated;
    }

    // --- Direct access, used by commands and the in-game state editor ---

    public Set<String> tags(String scope, String owner) {
        return store.tags(StateKey.of(StateScope.parse(scope), owner));
    }

    public Map<String, String> variables(String scope, String owner) {
        return store.variables(StateKey.of(StateScope.parse(scope), owner));
    }

    public boolean addTag(String scope, String owner, String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        StateKey key = StateKey.of(StateScope.parse(scope), owner);
        boolean changed = store.addTag(key, tag.trim());
        if (changed && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.addTag(playerUuid(key), tag.trim());
        }
        return changed;
    }

    public boolean removeTag(String scope, String owner, String tag) {
        if (tag == null || tag.isBlank()) {
            return false;
        }
        StateKey key = StateKey.of(StateScope.parse(scope), owner);
        boolean changed = store.removeTag(key, tag.trim());
        if (changed && key.scope() == StateScope.PLAYER) {
            hyExtrasBridge.removeTag(playerUuid(key), tag.trim());
        }
        return changed;
    }

    public boolean setVariable(String scope, String owner, String key, String value) {
        if (key == null || key.isBlank()) {
            return false;
        }
        StateKey stateKey = StateKey.of(StateScope.parse(scope), owner);
        String name = key.trim();
        String resolved = value == null ? "" : value;
        boolean changed = store.setVariable(stateKey, name, resolved);
        if (changed && stateKey.scope() == StateScope.PLAYER) {
            hyExtrasBridge.setVariable(playerUuid(stateKey), name, resolved);
        }
        return changed;
    }

    public boolean removeVariable(String scope, String owner, String key) {
        if (key == null || key.isBlank()) {
            return false;
        }
        StateKey stateKey = StateKey.of(StateScope.parse(scope), owner);
        String name = key.trim();
        boolean changed = store.removeVariable(stateKey, name);
        if (changed && stateKey.scope() == StateScope.PLAYER) {
            hyExtrasBridge.removeVariable(playerUuid(stateKey), name);
        }
        return changed;
    }

    public void putMetadata(String scope, String owner, Map<String, String> metadata) {
        store.putMetadata(StateKey.of(StateScope.parse(scope), owner), metadata);
    }

    /**
     * Folds a player's pre-scoped-state tags and variables into player scope, once, on first load.
     * Both halves are independently opt-out via config because a server that has already migrated
     * does not want the old rows re-applied over newer state.
     */
    public void migrateLegacyPlayer(PlayerQuestData data) {
        StateKey key = StateKey.of(StateScope.PLAYER, data.playerId().toString());
        if (migrateLegacyTags) {
            for (String tag : data.tags()) {
                if (store.addTag(key, tag)) {
                    hyExtrasBridge.addTag(data.playerId(), tag);
                }
            }
        }
        if (migrateLegacyVariables) {
            for (Map.Entry<String, String> variable : data.playerVariables().entrySet()) {
                // putIfAbsent semantics: never let a legacy row overwrite live scoped state.
                if (store.variable(key, variable.getKey()) == null
                        && store.setVariable(key, variable.getKey(), variable.getValue())) {
                    hyExtrasBridge.setVariable(data.playerId(), variable.getKey(), variable.getValue());
                }
            }
        }
    }

    /** Compares a variable against an authored expected value using the condition's operator. */
    public boolean compare(UUID playerId, TypedConfig condition, QuestTargetContext targetContext) {
        String actual = variable(playerId, condition, targetContext);
        String expected = condition.text("value", "");
        String operator = condition.text("operator", condition.text("op", "eq")).toLowerCase();
        return switch (operator) {
            case "ne", "!=", "not" -> actual == null || !actual.equals(expected);
            case "gt", ">" -> StateEntry.parseLong(actual) > StateEntry.parseLong(expected);
            case "gte", ">=" -> StateEntry.parseLong(actual) >= StateEntry.parseLong(expected);
            case "lt", "<" -> StateEntry.parseLong(actual) < StateEntry.parseLong(expected);
            case "lte", "<=" -> StateEntry.parseLong(actual) <= StateEntry.parseLong(expected);
            case "exists" -> actual != null;
            default -> expected.equals(actual);
        };
    }

    /** @deprecated use {@link StateScope#parse}; kept so existing callers keep compiling. */
    @Deprecated
    public static String normalizeScope(String scope) {
        return StateScope.parse(scope).id();
    }

    /** @deprecated use {@link StateScope#normalizeOwner}; kept so existing callers keep compiling. */
    @Deprecated
    public static String normalizeOwner(String scope, String owner) {
        return StateScope.parse(scope).normalizeOwner(owner);
    }

    // --- Internals ---

    /**
     * Resolves which owner an authored condition or event addresses. Returns null when the scope
     * needs a target the current context cannot supply — an {@code entity} tag fired by something
     * with no entity, for example — so the caller skips rather than writing under a blank owner.
     */
    private StateKey key(UUID playerId, TypedConfig config, QuestTargetContext targetContext) {
        StateScope scope = scope(config);
        String explicit = config.text("target", "");
        if (!explicit.isBlank()) {
            return StateKey.of(scope, explicit);
        }
        String owner = switch (scope) {
            case GLOBAL -> StateScope.GLOBAL_OWNER;
            case ENTITY -> targetContext == null ? null : targetContext.entityId();
            case BLOCK -> targetContext == null ? null : targetContext.blockId();
            case VOLUME -> targetContext == null ? null : targetContext.volumeKey();
            case PLAYER -> playerId == null ? null : playerId.toString();
        };
        if (owner == null || owner.isBlank()) {
            return scope == StateScope.GLOBAL ? StateKey.global() : null;
        }
        return StateKey.of(scope, owner);
    }

    /**
     * Legacy type aliases still resolve to a scope here as a safety net. Content is normalised to
     * explicit {@code scope} fields by the loader, so this only fires for definitions built in code.
     */
    private StateScope scope(TypedConfig config) {
        return switch (config.type()) {
            case "globalTag", "globalVariable" -> StateScope.GLOBAL;
            case "entityTag", "entityVariable" -> StateScope.ENTITY;
            case "blockTag", "blockVariable" -> StateScope.BLOCK;
            case "volumeTag", "volumeVariable" -> StateScope.VOLUME;
            default -> StateScope.parse(config.text("scope", "player"));
        };
    }

    private static String variableName(TypedConfig config) {
        return config.text("key", config.text("name", ""));
    }

    private void rememberMetadata(StateKey key, QuestTargetContext targetContext) {
        if (targetContext == null) {
            return;
        }
        Map<String, String> metadata = switch (key.scope()) {
            case ENTITY -> key.owner().equals(targetContext.entityId()) ? targetContext.entityMetadata() : null;
            case BLOCK -> key.owner().equals(targetContext.blockId()) ? targetContext.blockMetadata() : null;
            case VOLUME -> key.owner().equals(targetContext.volumeKey()) ? targetContext.volumeMetadata() : null;
            case PLAYER, GLOBAL -> null;
        };
        if (metadata != null) {
            store.putMetadata(key, metadata);
        }
    }

    /**
     * Player-scope owners are UUID strings, but an authored {@code target} could be anything. A
     * malformed one skips the HyExtras mirror rather than throwing out of a quest event.
     */
    private static UUID playerUuid(StateKey key) {
        try {
            return UUID.fromString(key.owner());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
