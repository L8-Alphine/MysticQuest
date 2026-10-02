package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.state.EntityIndexService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.UUID;
import java.util.function.Function;

/**
 * Resolves the {@code target} field of an authored action into concrete entity or player UUIDs.
 *
 * <p>Before this, an action could only ever address the acting player or an owner id typed out in
 * full, which made "tag the NPC the player is talking to" or "hide every guard" impossible to write.
 * The supported forms are:
 *
 * <ul>
 *   <li>{@code self} — the acting player (the default when no target is given)</li>
 *   <li>{@code context} — whatever the trigger was fired against: the interacted entity, or the
 *       entity that entered the volume</li>
 *   <li>{@code uuid:&lt;id&gt;} — one explicit entity or player</li>
 *   <li>{@code nearest} or {@code nearest:&lt;radius&gt;} — the closest tracked non-player entity to
 *       the acting player, default radius {@value #DEFAULT_NEAREST_RADIUS}</li>
 *   <li>{@code tag:&lt;tag&gt;} — every entity carrying that entity-scope tag</li>
 *   <li>{@code generation} — the MysticGeneration NPC the trigger fired against, addressed by its
 *       stable identity rather than its live entity UUID</li>
 *   <li>{@code generation:&lt;definition&gt;} — every live NPC spawned from that definition</li>
 *   <li>{@code party} — every member of the acting player's party, the player included</li>
 *   <li>{@code players} — every online player</li>
 * </ul>
 *
 * <p>An unresolvable selector yields an empty list rather than falling back to the player, so a
 * typo'd target never silently applies an effect to the wrong subject.
 */
public final class TargetSelector {
    /** Radius in blocks used by {@code nearest} when none is given. */
    public static final double DEFAULT_NEAREST_RADIUS = 8.0D;

    private final MysticStateStore store;
    private final EntityIndexService entityIndex;
    private final PlayerSessionService sessions;
    private final Function<UUID, Collection<UUID>> partyMembers;

    /** Null when MysticGeneration is absent or switched off; {@code generation} then resolves empty. */
    @Nullable
    private final MysticGenerationBridge generationBridge;

    public TargetSelector(
            MysticStateStore store,
            EntityIndexService entityIndex,
            PlayerSessionService sessions,
            Function<UUID, Collection<UUID>> partyMembers) {
        this(store, entityIndex, sessions, partyMembers, null);
    }

    public TargetSelector(
            MysticStateStore store,
            EntityIndexService entityIndex,
            PlayerSessionService sessions,
            Function<UUID, Collection<UUID>> partyMembers,
            @Nullable MysticGenerationBridge generationBridge) {
        this.store = store;
        this.entityIndex = entityIndex;
        this.sessions = sessions;
        this.partyMembers = partyMembers;
        this.generationBridge = generationBridge;
    }

    /** Resolves the {@code target} field of a definition, defaulting to the acting player. */
    public List<UUID> resolve(TypedConfig definition, UUID playerId, QuestTargetContext targetContext) {
        return resolve(definition.text("target", "self"), playerId, targetContext);
    }

    /** Resolves one selector expression. */
    public List<UUID> resolve(String selector, UUID playerId, QuestTargetContext targetContext) {
        if (selector == null || selector.isBlank() || selector.equalsIgnoreCase("self")) {
            return playerId == null ? List.of() : List.of(playerId);
        }
        String trimmed = selector.trim();
        int separator = trimmed.indexOf(':');
        String kind = (separator < 0 ? trimmed : trimmed.substring(0, separator)).toLowerCase(Locale.ROOT);
        String argument = separator < 0 ? "" : trimmed.substring(separator + 1).trim();

        return switch (kind) {
            case "context" -> single(targetContext == null ? null : targetContext.entityId());
            case "uuid", "entity" -> single(argument);
            case "nearest" -> nearest(playerId, argument);
            case "tag" -> taggedEntities(argument);
            case "generation", "npc" -> generationEntities(playerId, argument, targetContext);
            case "party" -> playerId == null ? List.of() : List.copyOf(partyMembers.apply(playerId));
            case "players" -> onlinePlayers();
            // A bare UUID with no prefix is still a valid, common way to name one entity.
            default -> single(trimmed);
        };
    }

    /** Resolves to exactly one target, or null. Used by actions that cannot fan out. */
    public UUID resolveSingle(TypedConfig definition, UUID playerId, QuestTargetContext targetContext) {
        return resolveSingle(definition.text("target", "self"), playerId, targetContext);
    }

    /** Resolves one selector expression to a single target, or null. */
    public UUID resolveSingle(String selector, UUID playerId, QuestTargetContext targetContext) {
        List<UUID> resolved = resolve(selector, playerId, targetContext);
        return resolved.isEmpty() ? null : resolved.get(0);
    }

    private static List<UUID> single(String raw) {
        UUID uuid = parseUuid(raw);
        return uuid == null ? List.of() : List.of(uuid);
    }

    private List<UUID> onlinePlayers() {
        List<UUID> online = new ArrayList<>();
        sessions.onlinePlayerIds().forEach(online::add);
        return online;
    }

    private List<UUID> taggedEntities(String tag) {
        if (tag == null || tag.isBlank()) {
            return List.of();
        }
        List<UUID> matches = new ArrayList<>();
        for (String owner : store.owners(StateScope.ENTITY)) {
            if (store.hasTag(StateKey.of(StateScope.ENTITY, owner), tag)) {
                UUID uuid = parseUuid(owner);
                if (uuid != null) {
                    matches.add(uuid);
                }
            }
        }
        return matches;
    }

    /**
     * Resolves MysticGeneration NPCs to their stable identities.
     *
     * <p>With no argument this is the NPC the trigger fired against; with one it is every live NPC
     * of that definition in the acting player's world. The returned UUIDs are generation identities,
     * not entity UUIDs, so tags and variables written against them survive a republished definition.
     */
    private List<UUID> generationEntities(UUID playerId, String definitionId, QuestTargetContext targetContext) {
        if (definitionId == null || definitionId.isBlank()) {
            return single(targetContext == null ? null : targetContext.generationUuid());
        }
        if (generationBridge == null) {
            return List.of();
        }
        Store<EntityStore> entityStore = storeOf(playerId);
        return entityStore == null ? List.of() : generationBridge.entitiesOf(entityStore, definitionId);
    }

    /** The entity store the acting player is in, or null while they are offline or unloaded. */
    @Nullable
    private Store<EntityStore> storeOf(UUID playerId) {
        if (playerId == null) {
            return null;
        }
        PlayerRef playerRef = sessions.playerRef(playerId);
        if (playerRef == null) {
            return null;
        }
        Ref<EntityStore> reference = playerRef.getReference();
        return reference == null || !reference.isValid() ? null : reference.getStore();
    }

    /**
     * Finds the closest tracked non-player entity to the acting player. Needs the player's position,
     * which only exists while they are online and their entity is loaded.
     */
    private List<UUID> nearest(UUID playerId, String rawRadius) {
        if (playerId == null) {
            return List.of();
        }
        PlayerRef playerRef = sessions.playerRef(playerId);
        if (playerRef == null) {
            return List.of();
        }
        Ref<EntityStore> reference = playerRef.getReference();
        if (reference == null || !reference.isValid()) {
            return List.of();
        }
        Store<EntityStore> entityStore = reference.getStore();
        if (entityStore == null) {
            return List.of();
        }
        TransformComponent transform = entityStore.getComponent(reference, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            return List.of();
        }
        UUID closest = entityIndex.findClosest(entityStore, transform.getPosition(), parseRadius(rawRadius));
        return closest == null ? List.of() : List.of(closest);
    }

    private static double parseRadius(String raw) {
        if (raw == null || raw.isBlank()) {
            return DEFAULT_NEAREST_RADIUS;
        }
        try {
            double radius = Double.parseDouble(raw.trim());
            return radius > 0.0D ? radius : DEFAULT_NEAREST_RADIUS;
        } catch (NumberFormatException ignored) {
            return DEFAULT_NEAREST_RADIUS;
        }
    }

    private static UUID parseUuid(String raw) {
        if (raw == null || raw.isBlank()) {
            return null;
        }
        try {
            return UUID.fromString(raw.trim());
        } catch (IllegalArgumentException notAUuid) {
            return null;
        }
    }
}
