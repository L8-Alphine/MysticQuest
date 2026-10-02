package org.hyzionstudios.mysticquests.narrative.session;

import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator.DocumentVersionException;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;

import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Creates, restores, persists, forks and releases {@link QuestSession}s (§3.2, QuestSessionService).
 *
 * <h2>Storage layout</h2>
 *
 * <p>Each session is one document in {@code sessions}. Each owner has an index document in
 * {@code session-index} listing their session ids, so restoring a player on join reads one index and
 * their sessions, never the whole collection. Both carry a schema version.
 *
 * <h2>Lifecycle</h2>
 *
 * <ul>
 *   <li><b>Restore.</b> {@link #load(SessionOwner)} on join reads the player's sessions, and their
 *       party's, into memory. Loading is idempotent.</li>
 *   <li><b>Change.</b> Any change marks the session dirty; {@link #flush()} writes dirty sessions and
 *       indexes. The runtime flushes on an interval, on quit and on shutdown.</li>
 *   <li><b>Release.</b> {@link #release(SessionOwner)} writes and unloads an owner's sessions once no
 *       online member needs them.</li>
 * </ul>
 *
 * <p>Unreadable documents, whether corrupt or from a newer release, are quarantined exactly like owner
 * state in {@code NarrativeStateStore}: reported, never overwritten.
 */
public final class QuestSessionService {
    static final String SESSIONS = "sessions";
    static final String INDEX = "session-index";
    private static final int SESSION_SCHEMA = 1;
    private static final int INDEX_SCHEMA = 1;

    private final DocumentStore documents;
    private final Clock clock;
    private final String serverId;
    private final Consumer<String> problems;
    private final Supplier<String> idGenerator;
    private final DocumentMigrator sessionMigrator = new DocumentMigrator("quest session", SESSION_SCHEMA);
    private final DocumentMigrator indexMigrator = new DocumentMigrator("session index", INDEX_SCHEMA);

    private final Map<String, QuestSession> sessions = new ConcurrentHashMap<>();
    private final Map<SessionOwner, Set<String>> indexes = new ConcurrentHashMap<>();
    private final Set<String> dirtySessions = ConcurrentHashMap.newKeySet();
    private final Set<SessionOwner> dirtyIndexes = ConcurrentHashMap.newKeySet();
    private final Set<String> quarantinedSessions = ConcurrentHashMap.newKeySet();
    private final Set<SessionOwner> quarantinedIndexes = ConcurrentHashMap.newKeySet();

    /** Sessions and session indexes whose documents could not be read, for the migration report. */
    public List<String> quarantined() {
        List<String> all = new ArrayList<>();
        quarantinedSessions.forEach(id -> all.add("session " + id));
        quarantinedIndexes.forEach(owner -> all.add("session index of " + owner));
        return all;
    }

    public QuestSessionService(DocumentStore documents, Clock clock, String serverId, Consumer<String> problems) {
        this(documents, clock, serverId, problems, () -> "qs-" + UUID.randomUUID());
    }

    /** @param idGenerator new session ids; injectable so tests get stable ids */
    public QuestSessionService(DocumentStore documents, Clock clock, String serverId, Consumer<String> problems,
                               Supplier<String> idGenerator) {
        this.documents = documents;
        this.clock = clock;
        this.serverId = serverId;
        this.problems = problems;
        this.idGenerator = idGenerator;
    }

    // --- Lookup and creation ---

    /** Every session of {@code owner}, loading them from storage the first time. */
    public List<QuestSession> load(SessionOwner owner) {
        Set<String> ids = indexes.computeIfAbsent(owner, this::readIndex);
        List<QuestSession> loaded = new ArrayList<>(ids.size());
        for (String id : List.copyOf(ids)) {
            QuestSession session = sessions.get(id);
            if (session == null) {
                session = readSession(id);
            }
            if (session != null) {
                loaded.add(session);
            }
        }
        return loaded;
    }

    /** The owner's active session for {@code storyKey}, if there is one. */
    public Optional<QuestSession> active(SessionOwner owner, String storyKey) {
        for (QuestSession session : load(owner)) {
            if (session.active() && session.storyKey().equals(storyKey)) {
                return Optional.of(session);
            }
        }
        return Optional.empty();
    }

    /**
     * The owner's active session for {@code storyKey}, created if they have none. Opening also stamps
     * the session with this server and the current content release.
     *
     * <h4>Lock order</h4>
     *
     * <p>Callers often hold a session's monitor already: a puzzle input whose output feeds another
     * puzzle arrives here from inside one. So the service lock is only ever taken <em>without</em>
     * then taking a session's monitor. The find-or-create runs under the service lock, and touching
     * the session happens after it is released. Every method here follows the same rule, because
     * the reverse order (service lock, then session) would deadlock against such a caller.
     */
    public QuestSession open(SessionOwner owner, String storyKey, String contentVersion) {
        QuestSession session;
        synchronized (this) {
            session = active(owner, storyKey).orElse(null);
            if (session == null) {
                session = new QuestSession(idGenerator.get(), owner, storyKey, contentVersion, clock.instant());
                adopt(session);
            }
        }
        String version = contentVersion == null ? "" : contentVersion;
        if (!session.contentVersion().equals(version)) {
            problems.accept("session " + session.id() + " continues under content " + version
                    + " (created under " + session.createdContentVersion() + ")");
        }
        session.touch(clock.instant(), serverId, version);
        return session;
    }

    /** A loaded session by id. Does not read storage: sessions are loaded through their owner. */
    public Optional<QuestSession> get(String sessionId) {
        return Optional.ofNullable(sessions.get(sessionId));
    }

    /** The {@code quest_session} state of a loaded session, or null; the state host's session route. */
    @Nullable
    public OwnerState sessionState(String sessionId) {
        QuestSession session = sessions.get(sessionId);
        return session == null ? null : session.state();
    }

    public Collection<QuestSession> loaded() {
        return List.copyOf(sessions.values());
    }

    // --- Lifecycle ---

    public void complete(QuestSession session) {
        session.setStatus(SessionStatus.COMPLETED, clock.instant());
    }

    public void abandon(QuestSession session) {
        session.setStatus(SessionStatus.ABANDONED, clock.instant());
    }

    /**
     * Gives a player who left a party a copy of the party's active sessions, or not, per
     * {@code policy}. See {@link PartyExitPolicy}.
     *
     * @return the copies created
     */
    public List<QuestSession> onMemberLeft(String partyId, UUID playerId, PartyExitPolicy policy) {
        if (policy != PartyExitPolicy.FORK) {
            return List.of();
        }
        List<QuestSession> forks = new ArrayList<>();
        SessionOwner party = SessionOwner.party(partyId);
        SessionOwner player = SessionOwner.player(playerId);
        for (QuestSession session : load(party)) {
            if (!session.active()) {
                continue;
            }
            // Copied under the session's monitor only, then adopted under the service lock only;
            // see the lock-order note on open().
            QuestSession fork = session.fork(idGenerator.get(), player, clock.instant());
            synchronized (this) {
                if (active(player, session.storyKey()).isPresent()) {
                    problems.accept(player + " left " + party + " but already has their own " + session.storyKey()
                            + " session; keeping theirs and not copying the party's");
                    continue;
                }
                adopt(fork);
            }
            forks.add(fork);
        }
        return forks;
    }

    /**
     * Closes a disbanded party's sessions. Under {@link PartyExitPolicy#FORK} each member first gets
     * their own copy; the party sessions are then archived either way, so a later party that
     * happens to reuse the id never inherits them.
     */
    public List<QuestSession> onPartyDisbanded(String partyId, Collection<UUID> members, PartyExitPolicy policy) {
        List<QuestSession> forks = new ArrayList<>();
        for (UUID member : members) {
            forks.addAll(onMemberLeft(partyId, member, policy));
        }
        for (QuestSession session : load(SessionOwner.party(partyId))) {
            if (session.active()) {
                session.setStatus(SessionStatus.ARCHIVED, clock.instant());
            }
        }
        return forks;
    }

    private void adopt(QuestSession session) {
        session.onChange(() -> dirtySessions.add(session.id()));
        sessions.put(session.id(), session);
        indexes.computeIfAbsent(session.owner(), this::readIndex).add(session.id());
        dirtyIndexes.add(session.owner());
        dirtySessions.add(session.id());
    }

    // --- Persistence ---

    /** Writes every dirty session and index. Safe from any thread and when nothing is dirty. */
    public void flush() {
        for (String id : List.copyOf(dirtySessions)) {
            dirtySessions.remove(id);
            writeSession(id);
        }
        for (SessionOwner owner : List.copyOf(dirtyIndexes)) {
            dirtyIndexes.remove(owner);
            writeIndex(owner);
        }
    }

    public boolean hasPendingChanges() {
        return !dirtySessions.isEmpty() || !dirtyIndexes.isEmpty();
    }

    /** Writes and unloads an owner's sessions. A session that fails to write stays loaded and dirty. */
    public void release(SessionOwner owner) {
        Set<String> ids = indexes.get(owner);
        if (ids == null) {
            return;
        }
        for (String id : List.copyOf(ids)) {
            if (dirtySessions.remove(id)) {
                writeSession(id);
            }
            if (!dirtySessions.contains(id)) {
                sessions.remove(id);
                quarantinedSessions.remove(id);
            }
        }
        if (dirtyIndexes.remove(owner)) {
            writeIndex(owner);
        }
        if (!dirtyIndexes.contains(owner)) {
            indexes.remove(owner);
            quarantinedIndexes.remove(owner);
        }
    }

    private Set<String> readIndex(SessionOwner owner) {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        try {
            Optional<ObjectNode> stored = documents.read(INDEX, owner.key());
            if (stored.isPresent()) {
                ObjectNode document = indexMigrator.migrate(stored.get()).document();
                document.path("sessions").forEach(id -> ids.add(id.asText()));
            }
        } catch (DocumentVersionException | IOException unreadable) {
            quarantinedIndexes.add(owner);
            problems.accept("session index for " + owner + " is quarantined and will not be saved: " + unreadable.getMessage());
        }
        return ids;
    }

    @Nullable
    private QuestSession readSession(String id) {
        if (quarantinedSessions.contains(id)) {
            return null;
        }
        try {
            Optional<ObjectNode> stored = documents.read(SESSIONS, id);
            if (stored.isEmpty()) {
                problems.accept("session " + id + " is indexed but has no document; dropping it from the index");
                indexes.forEach((owner, ids) -> {
                    if (ids.remove(id)) {
                        dirtyIndexes.add(owner);
                    }
                });
                return null;
            }
            DocumentMigrator.Migrated migrated = sessionMigrator.migrate(stored.get());
            List<String> skipped = new ArrayList<>();
            QuestSession session = QuestSession.fromJson(migrated.document(), skipped);
            skipped.forEach(problem -> problems.accept("session " + id + ": " + problem));
            QuestSession raced = sessions.putIfAbsent(id, session);
            if (raced != null) {
                return raced;
            }
            session.onChange(() -> dirtySessions.add(session.id()));
            if (migrated.changed()) {
                dirtySessions.add(id);
            }
            return session;
        } catch (DocumentVersionException | IOException | RuntimeException unreadable) {
            quarantinedSessions.add(id);
            problems.accept("session " + id + " is quarantined and will not be saved: " + unreadable.getMessage());
            return null;
        }
    }

    private void writeSession(String id) {
        QuestSession session = sessions.get(id);
        if (session == null || quarantinedSessions.contains(id)) {
            return;
        }
        try {
            documents.write(SESSIONS, id, sessionMigrator.stamp(session.toJson()));
        } catch (IOException | RuntimeException failure) {
            dirtySessions.add(id);
            problems.accept("failed to save session " + id + ", will retry: " + failure.getMessage());
        }
    }

    private void writeIndex(SessionOwner owner) {
        Set<String> ids = indexes.get(owner);
        if (ids == null || quarantinedIndexes.contains(owner)) {
            return;
        }
        try {
            if (ids.isEmpty()) {
                documents.delete(INDEX, owner.key());
                return;
            }
            ObjectNode document = JsonNodeFactory.instance.objectNode();
            indexMigrator.stamp(document);
            document.put("owner", owner.key());
            ArrayNode array = document.putArray("sessions");
            new TreeSet<>(ids).forEach(array::add);
            documents.write(INDEX, owner.key(), document);
        } catch (IOException | RuntimeException failure) {
            dirtyIndexes.add(owner);
            problems.accept("failed to save session index for " + owner + ", will retry: " + failure.getMessage());
        }
    }

    /** Owners currently loaded; for the debugger and for deciding what to release. */
    public Set<SessionOwner> loadedOwners() {
        return new LinkedHashSet<>(indexes.keySet());
    }

    /** Clock time, so collaborators stamp with the same clock as the sessions they touch. */
    public Instant now() {
        return clock.instant();
    }
}
