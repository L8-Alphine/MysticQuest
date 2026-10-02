package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator.DocumentVersionException;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Predicate;

/**
 * Persistent home of every non-session narrative owner, loaded on first use and written back in
 * coalesced batches.
 *
 * <p>Owners load lazily rather than all at startup. Player and quest owners grow with every player
 * who has ever joined, and only online players need theirs. The runtime prefetches a player's owners
 * when they join, so the lazy path rarely runs on a game thread.
 *
 * <p>Writes follow the same pattern as v1's {@code StateWriteQueue}: a change marks its owner dirty,
 * and {@link #flush()} writes each dirty owner once. A failed write leaves the owner dirty, so the
 * next flush retries it.
 *
 * <h2>Quarantine</h2>
 *
 * <p>If an owner's document cannot be read (corrupt, or written by a newer release), the owner is
 * <em>quarantined</em>: it runs on empty in-memory state, and this store never writes it back. The
 * alternative, an overwrite, would replace a player's real state with the empty placeholder. That
 * loss could not be undone. Quarantine only costs a degraded session until staff look at the
 * reported problem.
 */
public final class NarrativeStateStore implements StateHost {
    static final String COLLECTION = "state";
    private static final int SCHEMA_VERSION = 1;

    private final DocumentStore documents;
    private final DocumentMigrator migrator = new DocumentMigrator("owner state", SCHEMA_VERSION);
    private final Consumer<String> problems;
    private final Map<ScopeOwner, OwnerState> owners = new ConcurrentHashMap<>();
    private final Set<ScopeOwner> dirty = ConcurrentHashMap.newKeySet();
    private final Set<ScopeOwner> quarantined = ConcurrentHashMap.newKeySet();

    /** @param problems receives one line per unreadable document or failed write */
    public NarrativeStateStore(DocumentStore documents, Consumer<String> problems) {
        this.documents = documents;
        this.problems = problems;
    }

    @Override
    public OwnerState state(ScopeOwner owner) {
        if (owner.scope() == VariableScope.QUEST_SESSION) {
            throw new IllegalArgumentException("Session state lives in its session, not the state store: " + owner);
        }
        return owners.computeIfAbsent(owner, this::load);
    }

    /** The owner's state only if it is already loaded; never touches storage. For debug views. */
    public Optional<OwnerState> loaded(ScopeOwner owner) {
        return Optional.ofNullable(owners.get(owner));
    }

    /** Owners whose stored document could not be read; their state runs in memory and is never saved. */
    public Set<ScopeOwner> quarantined() {
        return Set.copyOf(quarantined);
    }

    public boolean isQuarantined(ScopeOwner owner) {
        return quarantined.contains(owner);
    }

    private OwnerState load(ScopeOwner owner) {
        OwnerState state = new OwnerState();
        if (owner.scope().persistent()) {
            try {
                Optional<ObjectNode> stored = documents.read(COLLECTION, owner.key());
                if (stored.isPresent()) {
                    DocumentMigrator.Migrated migrated = migrator.migrate(stored.get());
                    List<String> skipped = new ArrayList<>();
                    state = OwnerState.fromJson(migrated.document().get("state"), skipped);
                    skipped.forEach(problem -> problems.accept(owner + ": " + problem));
                    if (migrated.changed() || !skipped.isEmpty()) {
                        dirty.add(owner);
                    }
                }
            } catch (DocumentVersionException | IOException unreadable) {
                quarantined.add(owner);
                problems.accept(owner + " is quarantined and will not be saved: " + unreadable.getMessage());
            }
        }
        if (owner.scope().persistent()) {
            state.onChange(() -> dirty.add(owner));
        }
        return state;
    }

    /** Writes every dirty owner. Safe to call from any thread, and when nothing is dirty. */
    public void flush() {
        for (ScopeOwner owner : List.copyOf(dirty)) {
            dirty.remove(owner);
            write(owner);
        }
    }

    public boolean hasPendingChanges() {
        return !dirty.isEmpty();
    }

    private void write(ScopeOwner owner) {
        if (quarantined.contains(owner)) {
            return;
        }
        OwnerState state = owners.get(owner);
        try {
            if (state == null || state.isEmpty()) {
                documents.delete(COLLECTION, owner.key());
                return;
            }
            ObjectNode document = JsonNodeFactory.instance.objectNode();
            migrator.stamp(document);
            document.put("scope", owner.scope().id());
            document.put("owner", owner.ownerId());
            document.set("state", state.toJson());
            documents.write(COLLECTION, owner.key(), document);
        } catch (IOException | RuntimeException failure) {
            dirty.add(owner);
            problems.accept("failed to save " + owner + ", will retry: " + failure.getMessage());
        }
    }

    /**
     * Writes and unloads every loaded owner matching {@code filter}; used when a player leaves. A
     * temporary owner is simply dropped, which is what makes temporary scope temporary.
     */
    public void evict(Predicate<ScopeOwner> filter) {
        for (ScopeOwner owner : List.copyOf(owners.keySet())) {
            if (!filter.test(owner)) {
                continue;
            }
            if (dirty.remove(owner)) {
                write(owner);
            }
            if (!dirty.contains(owner)) {
                owners.remove(owner);
                quarantined.remove(owner);
            }
        }
    }

    /** Drops expired tags from every loaded owner. Expired tags are already invisible to reads. */
    public int purgeExpired(Instant now) {
        int purged = 0;
        for (OwnerState state : owners.values()) {
            purged += state.purgeExpired(now);
        }
        return purged;
    }

    public Set<ScopeOwner> loadedOwners() {
        return Set.copyOf(owners.keySet());
    }
}
