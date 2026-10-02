package org.hyzionstudios.mysticquests.narrative.session;

import org.hyzionstudios.mysticquests.narrative.action.TransitionLedger;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * The authoritative narrative context for one player or one party in one story (§3.1 of the 2.0
 * specification).
 *
 * <p>A session carries everything that makes "this player's version of the story" persistent: its own
 * tags and variables ({@code quest_session} scope), logical trigger overrides, subsystem components
 * such as puzzle state, the transition ledger that keeps rewards from repeating, and checkpoints. It
 * deliberately carries no live engine handles. Everything the world shows for a session is rebuilt
 * from this state on reconnect, restart or transfer (§22).
 *
 * <p>Thread-safety: the state and the ledger are concurrent. Lifecycle changes, checkpoints and
 * components are guarded by the session's monitor. Subsystems that read-modify-write a component
 * should hold that monitor too ({@code synchronized (session) { … }}).
 */
public final class QuestSession implements TransitionLedger {
    /** Oldest checkpoints are dropped past this, so a looping story cannot grow a session without bound. */
    static final int MAX_CHECKPOINTS = 16;

    private final String id;
    private final SessionOwner owner;
    private final String storyKey;
    private final Instant createdAt;
    private final String createdContentVersion;
    private final OwnerState state;
    private final Map<String, SessionComponent> components = new ConcurrentHashMap<>();
    /** Components not yet decoded, or owned by a subsystem this build does not have; kept verbatim. */
    private final Map<String, ObjectNode> rawComponents = new ConcurrentHashMap<>();
    private final Set<String> ledger = ConcurrentHashMap.newKeySet();
    private final Set<String> permanentLedger = ConcurrentHashMap.newKeySet();
    private final LinkedHashMap<String, Checkpoint> checkpoints = new LinkedHashMap<>();

    private volatile String contentVersion;
    private volatile Instant updatedAt;
    private volatile SessionStatus status = SessionStatus.ACTIVE;
    @Nullable
    private volatile String currentNode;
    @Nullable
    private volatile String lastServer;
    private volatile Runnable changeListener = () -> {
    };

    /** A named snapshot that rollback restores. */
    public record Checkpoint(String label, Instant at, ObjectNode snapshot) {
    }

    QuestSession(String id, SessionOwner owner, String storyKey, String contentVersion, Instant now) {
        this(id, owner, storyKey, contentVersion, now, contentVersion, new OwnerState());
    }

    private QuestSession(String id, SessionOwner owner, String storyKey, String contentVersion,
                         Instant createdAt, String createdContentVersion, OwnerState state) {
        this.id = Objects.requireNonNull(id, "id");
        this.owner = Objects.requireNonNull(owner, "owner");
        this.storyKey = Objects.requireNonNull(storyKey, "storyKey");
        this.contentVersion = contentVersion == null ? "" : contentVersion;
        this.createdAt = createdAt;
        this.updatedAt = createdAt;
        this.createdContentVersion = createdContentVersion == null ? "" : createdContentVersion;
        this.state = state;
        this.state.onChange(this::markChanged);
    }

    public String id() {
        return id;
    }

    public SessionOwner owner() {
        return owner;
    }

    /** Which story this is a session of, usually the quest id. One active session per owner and story. */
    public String storyKey() {
        return storyKey;
    }

    public SessionStatus status() {
        return status;
    }

    public boolean active() {
        return status == SessionStatus.ACTIVE;
    }

    /** {@code quest_session} scope state. */
    public OwnerState state() {
        return state;
    }

    /** The content release this session last ran against. */
    public String contentVersion() {
        return contentVersion;
    }

    /** The content release this session was created under; with {@link #contentVersion} it shows drift (§24). */
    public String createdContentVersion() {
        return createdContentVersion;
    }

    public Instant createdAt() {
        return createdAt;
    }

    public Instant updatedAt() {
        return updatedAt;
    }

    @Nullable
    public String currentNode() {
        return currentNode;
    }

    @Nullable
    public String lastServer() {
        return lastServer;
    }

    void onChange(Runnable listener) {
        this.changeListener = listener == null ? () -> {
        } : listener;
    }

    /** Records that something in this session changed, so it is saved on the next flush. */
    public void markChanged() {
        changeListener.run();
    }

    /**
     * Records which server and content release are running this session. Only an actual change
     * marks the session dirty: sessions are opened on every input, and a player standing in a gated
     * volume must not rewrite their session every flush.
     */
    synchronized void touch(Instant now, String serverId, String contentVersion) {
        boolean changed = false;
        if (!Objects.equals(lastServer, serverId)) {
            lastServer = serverId;
            changed = true;
        }
        if (contentVersion != null && !contentVersion.equals(this.contentVersion)) {
            this.contentVersion = contentVersion;
            changed = true;
        }
        if (changed) {
            updatedAt = now;
            markChanged();
        }
    }

    synchronized void setStatus(SessionStatus status, Instant now) {
        if (this.status != status) {
            this.status = status;
            this.updatedAt = now;
            markChanged();
        }
    }

    public synchronized void setCurrentNode(@Nullable String node) {
        if (!Objects.equals(currentNode, node)) {
            currentNode = node;
            markChanged();
        }
    }

    // --- Components ---

    /**
     * The component under {@code key}: decoded from storage on first access, or created by
     * {@code factory} when absent. A decoder that throws leaves the stored form untouched, so a bad
     * read never overwrites the saved data, and reports through the exception.
     */
    public synchronized <T extends SessionComponent> T component(
            String key, Class<T> type, Function<ObjectNode, T> decoder, Supplier<T> factory) {
        SessionComponent existing = components.get(key);
        if (existing != null) {
            return type.cast(existing);
        }
        ObjectNode raw = rawComponents.get(key);
        T created = raw != null ? decoder.apply(raw) : factory.get();
        rawComponents.remove(key);
        components.put(key, created);
        if (raw == null) {
            markChanged();
        }
        return created;
    }

    /** The component if one exists, without creating it. */
    public synchronized <T extends SessionComponent> Optional<T> existingComponent(
            String key, Class<T> type, Function<ObjectNode, T> decoder) {
        if (!components.containsKey(key) && !rawComponents.containsKey(key)) {
            return Optional.empty();
        }
        return Optional.of(component(key, type, decoder, () -> {
            throw new IllegalStateException("unreachable");
        }));
    }

    public synchronized void putComponent(String key, SessionComponent component) {
        rawComponents.remove(key);
        components.put(key, component);
        markChanged();
    }

    public synchronized boolean removeComponent(String key) {
        boolean removed = components.remove(key) != null | rawComponents.remove(key) != null;
        if (removed) {
            markChanged();
        }
        return removed;
    }

    public synchronized Set<String> componentKeys() {
        Set<String> keys = new TreeSet<>(components.keySet());
        keys.addAll(rawComponents.keySet());
        return keys;
    }

    // --- Transition ledger ---

    @Override
    public boolean applied(String key) {
        return ledger.contains(key) || permanentLedger.contains(key);
    }

    @Override
    public void record(String key, boolean permanent) {
        boolean added = permanent ? permanentLedger.add(key) : ledger.add(key);
        if (added) {
            markChanged();
        }
    }

    /** Forgets ordinary keys starting with {@code prefix}; used when a repeatable puzzle resets. */
    public void forgetLedger(String prefix) {
        if (ledger.removeIf(key -> key.startsWith(prefix))) {
            markChanged();
        }
    }

    public int ledgerSize() {
        return ledger.size() + permanentLedger.size();
    }

    // --- Checkpoints ---

    /** Snapshots state, components, node and ordinary ledger under {@code label}, replacing any older one. */
    public synchronized Checkpoint checkpoint(String label, Instant now) {
        ObjectNode snapshot = JsonNodeFactory.instance.objectNode();
        snapshot.set("state", state.toJson());
        snapshot.set("components", encodeComponents());
        if (currentNode != null) {
            snapshot.put("currentNode", currentNode);
        }
        snapshot.set("ledger", sorted(ledger));
        Checkpoint checkpoint = new Checkpoint(label, now, snapshot);
        checkpoints.remove(label);
        checkpoints.put(label, checkpoint);
        while (checkpoints.size() > MAX_CHECKPOINTS) {
            checkpoints.remove(checkpoints.keySet().iterator().next());
        }
        markChanged();
        return checkpoint;
    }

    /**
     * Restores the snapshot taken under {@code label}. Permanent ledger keys survive, so effects
     * outside the session (items, money, commands) are never repeated by replaying the story.
     *
     * @return false when no checkpoint has that label
     */
    public synchronized boolean rollback(String label, List<String> problems) {
        Checkpoint checkpoint = checkpoints.get(label);
        if (checkpoint == null) {
            return false;
        }
        ObjectNode snapshot = checkpoint.snapshot();
        state.replaceWith(OwnerState.fromJson(snapshot.get("state"), problems));
        components.clear();
        rawComponents.clear();
        snapshot.path("components").properties().forEach(entry -> {
            if (entry.getValue().isObject()) {
                rawComponents.put(entry.getKey(), ((ObjectNode) entry.getValue()).deepCopy());
            }
        });
        currentNode = snapshot.hasNonNull("currentNode") ? snapshot.get("currentNode").asText() : null;
        ledger.clear();
        snapshot.path("ledger").forEach(key -> ledger.add(key.asText()));
        markChanged();
        return true;
    }

    public synchronized List<Checkpoint> checkpoints() {
        return List.copyOf(checkpoints.values());
    }

    // --- Serialisation ---

    synchronized ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("id", id);
        node.put("owner", owner.key());
        node.put("story", storyKey);
        node.put("status", status.name());
        node.put("contentVersion", contentVersion);
        node.put("createdContentVersion", createdContentVersion);
        node.put("createdAt", createdAt.toString());
        node.put("updatedAt", updatedAt.toString());
        if (currentNode != null) {
            node.put("currentNode", currentNode);
        }
        if (lastServer != null) {
            node.put("lastServer", lastServer);
        }
        node.set("state", state.toJson());
        node.set("components", encodeComponents());
        node.set("ledger", sorted(ledger));
        node.set("permanentLedger", sorted(permanentLedger));
        ArrayNode checkpointArray = node.putArray("checkpoints");
        for (Checkpoint checkpoint : checkpoints.values()) {
            ObjectNode entry = checkpointArray.addObject();
            entry.put("label", checkpoint.label());
            entry.put("at", checkpoint.at().toString());
            entry.set("snapshot", checkpoint.snapshot().deepCopy());
        }
        return node;
    }

    static QuestSession fromJson(ObjectNode node, List<String> problems) {
        String id = text(node, "id");
        SessionOwner owner = SessionOwner.parse(text(node, "owner"));
        if (owner == null) {
            throw new IllegalArgumentException("session " + id + " has an unreadable owner '" + node.path("owner").asText() + "'");
        }
        QuestSession session = new QuestSession(
                id,
                owner,
                text(node, "story"),
                node.path("contentVersion").asText(""),
                Instant.parse(text(node, "createdAt")),
                node.path("createdContentVersion").asText(""),
                OwnerState.fromJson(node.get("state"), problems));
        session.updatedAt = node.hasNonNull("updatedAt") ? Instant.parse(node.get("updatedAt").asText()) : session.createdAt;
        try {
            session.status = SessionStatus.valueOf(node.path("status").asText("ACTIVE"));
        } catch (IllegalArgumentException unknown) {
            problems.add("session " + id + " has unknown status '" + node.path("status").asText() + "'; treating it as ACTIVE");
        }
        session.currentNode = node.hasNonNull("currentNode") ? node.get("currentNode").asText() : null;
        session.lastServer = node.hasNonNull("lastServer") ? node.get("lastServer").asText() : null;
        node.path("components").properties().forEach(entry -> {
            if (entry.getValue().isObject()) {
                session.rawComponents.put(entry.getKey(), ((ObjectNode) entry.getValue()).deepCopy());
            }
        });
        node.path("ledger").forEach(key -> session.ledger.add(key.asText()));
        node.path("permanentLedger").forEach(key -> session.permanentLedger.add(key.asText()));
        for (JsonNode entry : node.path("checkpoints")) {
            if (entry.path("snapshot").isObject() && entry.hasNonNull("label")) {
                session.checkpoints.put(entry.get("label").asText(), new Checkpoint(
                        entry.get("label").asText(),
                        Instant.parse(entry.path("at").asText(session.createdAt.toString())),
                        ((ObjectNode) entry.get("snapshot")).deepCopy()));
            }
        }
        return session;
    }

    /**
     * A copy owned by someone else, with a new id: what a member keeps when they leave a party
     * session (§8.1). The copy carries the whole ledger, permanent keys included, so rewards the
     * member already earned in the party are not paid again in their own copy.
     */
    synchronized QuestSession fork(String newId, SessionOwner newOwner, Instant now) {
        QuestSession copy = new QuestSession(newId, newOwner, storyKey, contentVersion, now, createdContentVersion, state.copy());
        copy.currentNode = currentNode;
        encodeComponents().properties().forEach(entry ->
                copy.rawComponents.put(entry.getKey(), ((ObjectNode) entry.getValue()).deepCopy()));
        copy.ledger.addAll(ledger);
        copy.permanentLedger.addAll(permanentLedger);
        return copy;
    }

    private ObjectNode encodeComponents() {
        ObjectNode encoded = JsonNodeFactory.instance.objectNode();
        Map<String, ObjectNode> all = new TreeMap<>();
        rawComponents.forEach((key, raw) -> all.put(key, raw.deepCopy()));
        components.forEach((key, component) -> all.put(key, component.toJson()));
        all.forEach(encoded::set);
        return encoded;
    }

    private static ArrayNode sorted(Set<String> keys) {
        ArrayNode array = JsonNodeFactory.instance.arrayNode();
        new TreeSet<>(keys).forEach(array::add);
        return array;
    }

    private static String text(ObjectNode node, String field) {
        JsonNode value = node.get(field);
        if (value == null || !value.isTextual() || value.asText().isBlank()) {
            throw new IllegalArgumentException("session document is missing \"" + field + "\"");
        }
        return value.asText();
    }

    /** One-line summary for logs and the debugger. */
    @Override
    public String toString() {
        return id + " [" + owner + " " + storyKey + " " + status + "]";
    }
}
