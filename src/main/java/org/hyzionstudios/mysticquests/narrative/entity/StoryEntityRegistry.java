package org.hyzionstudios.mysticquests.narrative.entity;

import org.hyzionstudios.mysticquests.narrative.PresentationLayer;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator.DocumentVersionException;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Which entities belong to which story session (§8 of the 2.0 specification).
 *
 * <p>A claimed entity is part of one audience's story. Only that audience may see it, target it, be
 * targeted by it, damage it, be damaged by it, or interact with it. Player A's boss and player B's
 * boss can then stand in the same room, each invisible and harmless to the other player. An
 * unclaimed entity is ordinary world content and nothing here restricts it.
 *
 * <h2>Identity</h2>
 *
 * <p>Claims name an entity the way content can name it durably: {@code uuid:<entity uuid>}, or
 * {@code generation:<stable id>} for a MysticGeneration NPC. Republishing a MysticGeneration
 * definition replaces its NPCs with new entity UUIDs but keeps their stable ids. A generation claim
 * is therefore re-bound to whichever live entity carries that id, each time the entity index sees it
 * ({@link #observe}).
 *
 * <p>Claims persist (collection {@code story-entities}) independently of session loading. A claimed
 * boss that the world saved stays hidden from everyone else across a restart, even before its owner
 * rejoins. The live entity binding is runtime-only and rebuilt by observation.
 */
public final class StoryEntityRegistry implements PresentationLayer {
    static final String COLLECTION = "story-entities";

    /** One claimed entity. */
    public record Claim(String key, String sessionId, SessionOwner owner, String story, Instant claimedAt) {
    }

    private final DocumentStore documents;
    private final Clock clock;
    private final Function<UUID, Optional<String>> partyOf;
    private final Consumer<String> problems;
    private final DocumentMigrator versions = new DocumentMigrator("story entity claim", 1);

    /** Claims by key ({@code kind:id}). */
    private final Map<String, Claim> claims = new ConcurrentHashMap<>();
    /** Live entity UUID to claim: direct UUID claims, plus generation claims bound by observation. */
    private final Map<UUID, Claim> live = new ConcurrentHashMap<>();

    public StoryEntityRegistry(DocumentStore documents, Clock clock, Function<UUID, Optional<String>> partyOf,
                               Consumer<String> problems) {
        this.documents = documents;
        this.clock = clock;
        this.partyOf = partyOf;
        this.problems = problems;
        load();
    }

    private void load() {
        try {
            for (String key : documents.list(COLLECTION)) {
                try {
                    Optional<ObjectNode> stored = documents.read(COLLECTION, key);
                    if (stored.isEmpty()) {
                        continue;
                    }
                    ObjectNode node = versions.migrate(stored.get()).document();
                    SessionOwner owner = SessionOwner.parse(node.path("owner").asText());
                    if (owner == null) {
                        problems.accept("story entity claim " + key + " has an unreadable owner; ignored");
                        continue;
                    }
                    install(new Claim(key, node.path("session").asText(), owner, node.path("story").asText(),
                            Instant.parse(node.path("claimedAt").asText())));
                } catch (DocumentVersionException | RuntimeException unreadable) {
                    problems.accept("story entity claim " + key + " could not be read and is ignored: " + unreadable.getMessage());
                }
            }
        } catch (IOException failure) {
            problems.accept("story entity claims could not be listed: " + failure.getMessage());
        }
    }

    /**
     * Puts an entity into a session's story. A later claim by another session replaces the earlier
     * one: the entity can belong to one story at a time.
     */
    public Claim claim(EntityRefValue entity, String sessionId, SessionOwner owner, String story) {
        Claim claim = new Claim(key(entity), sessionId, owner, story, clock.instant());
        Claim previous = claims.get(claim.key());
        if (previous != null && !previous.sessionId().equals(sessionId)) {
            problems.accept("story entity " + claim.key() + " moves from session " + previous.sessionId() + " to " + sessionId);
        }
        live.values().removeIf(existing -> existing.key().equals(claim.key()));
        install(claim);
        ObjectNode document = versions.stamp(JsonNodeFactory.instance.objectNode());
        document.put("key", claim.key());
        document.put("session", sessionId);
        document.put("owner", owner.key());
        document.put("story", story);
        document.put("claimedAt", claim.claimedAt().toString());
        try {
            documents.write(COLLECTION, claim.key(), document);
        } catch (IOException failure) {
            problems.accept("story entity claim " + claim.key() + " could not be saved; it lasts until restart: " + failure.getMessage());
        }
        return claim;
    }

    /** Returns an entity to the ordinary world. */
    public boolean release(EntityRefValue entity) {
        String key = key(entity);
        Claim removed = claims.remove(key);
        live.values().removeIf(existing -> existing.key().equals(key));
        try {
            documents.delete(COLLECTION, key);
        } catch (IOException failure) {
            problems.accept("story entity claim " + key + " could not be deleted: " + failure.getMessage());
        }
        return removed != null;
    }

    /** Releases every entity a session claimed, for example when its story ends. */
    public int releaseSession(String sessionId) {
        int released = 0;
        for (Claim claim : List.copyOf(claims.values())) {
            if (claim.sessionId().equals(sessionId) && release(parse(claim.key()))) {
                released++;
            }
        }
        return released;
    }

    /**
     * Binds a live entity to a generation claim on its stable id. Called for every indexed entity
     * each tick, so with no generation claims it costs one map lookup and allocates nothing.
     */
    public void observe(UUID entityUuid, @Nullable UUID generationUuid) {
        if (entityUuid == null || generationUuid == null || claims.isEmpty()) {
            return;
        }
        Claim claim = claims.get("generation:" + generationUuid);
        if (claim != null && live.get(entityUuid) != claim) {
            live.put(entityUuid, claim);
        }
    }

    /**
     * The live entity an authored reference points at: the UUID itself for {@code uuid:}, or the
     * body last observed for a claimed {@code generation:} id. Empty when it cannot be resolved.
     */
    public Optional<UUID> liveBody(EntityRefValue entity) {
        if (entity.kind().equals("uuid")) {
            try {
                return Optional.of(UUID.fromString(entity.id()));
            } catch (IllegalArgumentException invalid) {
                return Optional.empty();
            }
        }
        String key = key(entity);
        for (Map.Entry<UUID, Claim> body : live.entrySet()) {
            if (body.getValue().key().equals(key)) {
                return Optional.of(body.getKey());
            }
        }
        return Optional.empty();
    }

    /** The claim on a live entity, or null when it is ordinary world content. */
    @Nullable
    public Claim claimOf(UUID entityUuid) {
        return entityUuid == null || live.isEmpty() ? null : live.get(entityUuid);
    }

    /** No live entity is currently claimed; the isolation systems skip all work. */
    public boolean isEmpty() {
        return live.isEmpty();
    }

    /** Any claim exists, live or waiting to be observed; gates the per-entity identity read. */
    public boolean hasClaims() {
        return !claims.isEmpty();
    }

    @Override
    public boolean wantsObservation() {
        return hasClaims();
    }

    @Override
    public boolean hides(UUID viewer, UUID entity) {
        return !allows(viewer, entity);
    }

    /**
     * Whether {@code player} belongs to the audience that owns {@code claim}: the player who owns the
     * session, or a current member of the party that does.
     */
    public boolean allows(UUID player, Claim claim) {
        if (player == null || claim == null) {
            return claim == null;
        }
        return switch (claim.owner().kind()) {
            case PLAYER -> claim.owner().id().equals(player.toString());
            case PARTY -> partyOf.apply(player).map(claim.owner().id()::equals).orElse(false);
        };
    }

    /**
     * Whether {@code player} may see, target, be targeted by, damage, be damaged by, or interact with
     * this entity. Unclaimed entities allow everyone.
     */
    public boolean allows(UUID player, UUID entityUuid) {
        Claim claim = claimOf(entityUuid);
        return claim == null || allows(player, claim);
    }

    /** Live story entities {@code viewer} must not be shown. Empty, and allocation-free, when nothing is claimed. */
    public Set<UUID> hiddenFrom(UUID viewer) {
        if (live.isEmpty()) {
            return Set.of();
        }
        Set<UUID> hidden = new HashSet<>();
        live.forEach((entity, claim) -> {
            if (!allows(viewer, claim)) {
                hidden.add(entity);
            }
        });
        return hidden;
    }

    public List<Claim> claims() {
        return List.copyOf(claims.values());
    }

    private void install(Claim claim) {
        claims.put(claim.key(), claim);
        EntityRefValue entity = parse(claim.key());
        if (entity.kind().equals("uuid")) {
            try {
                live.put(UUID.fromString(entity.id()), claim);
            } catch (IllegalArgumentException invalid) {
                problems.accept("story entity claim " + claim.key() + " names an invalid UUID");
            }
        }
    }

    /** Only {@code uuid} and {@code generation} references can be resolved to live entities. */
    public static boolean supported(EntityRefValue entity) {
        return entity.kind().equals("uuid") || entity.kind().equals("generation");
    }

    static String key(EntityRefValue entity) {
        return entity.kind() + ":" + entity.id();
    }

    private static EntityRefValue parse(String key) {
        int colon = key.indexOf(':');
        return new EntityRefValue(key.substring(0, colon), key.substring(colon + 1));
    }
}
