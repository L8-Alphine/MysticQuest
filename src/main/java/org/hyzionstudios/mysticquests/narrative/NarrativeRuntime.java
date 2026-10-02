package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.action.ActionExecutor;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.action.builtin.CheckpointAction;
import org.hyzionstudios.mysticquests.narrative.action.builtin.CutsceneActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.EntityActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.MediaActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.OverlayActions;
import org.hyzionstudios.mysticquests.narrative.cutscene.QuestCutsceneService;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService;
import org.hyzionstudios.mysticquests.narrative.overlay.WorldOverlayRegistry;
import org.hyzionstudios.mysticquests.narrative.action.builtin.PuzzleActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.SignalAction;
import org.hyzionstudios.mysticquests.narrative.action.builtin.StateActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.TriggerActions;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionEvaluator;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.session.AudienceResolver;
import org.hyzionstudios.mysticquests.narrative.session.PartyExitPolicy;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.NarrativeStateStore;
import org.hyzionstudios.mysticquests.narrative.state.QuestTagService;
import org.hyzionstudios.mysticquests.narrative.state.QuestVariableService;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeResolver;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.state.StateHost;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.trigger.QuestTriggerService;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService;

import com.fasterxml.jackson.databind.JsonNode;

import java.time.Clock;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Function;

/**
 * Assembles the narrative runtime: state, sessions, conditions, actions, triggers and puzzles.
 *
 * <p>Engine-free by design. Everything Hytale-specific (the trigger volume adapter, the v1 bridges,
 * the commands) sits outside and talks to this through plain types. The whole runtime can therefore
 * be built in a unit test over an in-memory store, and "restart" means building a second runtime
 * over the same store.
 *
 * <h2>Session-scope routing</h2>
 *
 * <p>{@code quest_session} state is not stored in the {@link NarrativeStateStore}. It lives inside its
 * session document, so a session and its state persist, transfer and roll back together. The
 * {@link StateHost} handed to the services sends session owners to the session service and every
 * other owner to the store.
 */
public final class NarrativeRuntime implements AutoCloseable {
    /** Construction parameters; see each field. */
    public record Settings(
            DocumentStore documents,
            Clock clock,
            /* Stable id of this server; owns SERVER-scope state and global trigger overrides. */
            String serverId,
            String networkId,
            ScopeSupport scopes,
            /* A player's current party id, if a party provider knows one. */
            Function<UUID, Optional<String>> partyOf,
            /* Namespaces where undeclared tags and variables are tolerated, besides "legacy". */
            Collection<String> openNamespaces,
            SignalAction.Sink signals,
            PartyExitPolicy partyExitPolicy,
            /* Flush interval in milliseconds; 0 disables the background writer (tests). */
            long flushIntervalMillis,
            /* Receives one line per problem: unreadable documents, failed actions, migrations. */
            Consumer<String> problems,
            /* Receives one line per audited staff intervention, alongside the stored record. */
            Consumer<String> auditLog) {
    }

    private final Settings settings;
    private final AtomicReference<NarrativeContent> content = new AtomicReference<>(NarrativeContent.empty());
    private final NarrativeStateStore store;
    private final QuestSessionService sessions;
    private final ScopeResolver resolver;
    private final QuestTagService tags;
    private final QuestVariableService variables;
    private final ConditionTypeRegistry conditionTypes = new ConditionTypeRegistry();
    private final ActionTypeRegistry actionTypes = new ActionTypeRegistry();
    private final ConditionEvaluator conditions;
    private final ActionExecutor executor;
    private final AudienceResolver audiences;
    private final TriggerActivationService activation;
    private final QuestPuzzleService puzzles;
    private final QuestTriggerService triggers;
    private final NarrativeAudit audit;
    private final StoryEntityRegistry storyEntities;
    private final WorldOverlayRegistry overlays;
    private final QuestMediaService media;
    private final QuestCutsceneService cutscenes;
    private final ScheduledExecutorService writer;

    public NarrativeRuntime(Settings settings) {
        this.settings = settings;
        Consumer<String> problems = settings.problems();
        this.store = new NarrativeStateStore(settings.documents(), problems);
        this.sessions = new QuestSessionService(settings.documents(), settings.clock(), settings.serverId(), problems);
        StateHost host = owner -> owner.scope() == VariableScope.QUEST_SESSION
                ? sessions.sessionState(owner.ownerId())
                : store.state(owner);
        this.resolver = new ScopeResolver(settings.scopes(), settings.serverId(), settings.networkId());
        this.tags = new QuestTagService(() -> content.get().schemas(), resolver, host, settings.clock());
        this.variables = new QuestVariableService(() -> content.get().schemas(), resolver, host, problems);
        this.conditions = new ConditionEvaluator(tags, variables, conditionTypes, problems);
        this.executor = new ActionExecutor(actionTypes, problems);
        this.audiences = new AudienceResolver(settings.partyOf());
        this.activation = new TriggerActivationService(host, settings.serverId(), settings.partyOf(), this::activeSessionIds);
        this.puzzles = new QuestPuzzleService(
                () -> content.get().puzzles(),
                packageId -> content.get().version(packageId),
                sessions, audiences, executor, conditions, settings.clock(), problems);
        this.triggers = new QuestTriggerService(content::get, activation, puzzles);
        this.audit = new NarrativeAudit(settings.documents(), settings.clock(), settings.serverId(), settings.auditLog());
        this.storyEntities = new StoryEntityRegistry(settings.documents(), settings.clock(), settings.partyOf(), problems);
        this.overlays = new WorldOverlayRegistry(() -> content.get().overlays(), activation);
        this.media = new QuestMediaService(content::get, host, settings.serverId(), settings.partyOf(), this::activeSessionIds,
                sessions, settings.clock(), problems);
        this.cutscenes = new QuestCutsceneService(() -> content.get().cutscenes(), packageId -> content.get().version(packageId),
                sessions, audiences, executor, media, settings.clock(), problems);

        StateActions.register(actionTypes, tags, variables);
        EntityActions.register(actionTypes, storyEntities, sessions, variables);
        OverlayActions.register(actionTypes, activation);
        MediaActions.register(actionTypes, media, variables);
        CutsceneActions.register(actionTypes, cutscenes);
        CheckpointAction.register(actionTypes, sessions, settings.clock());
        TriggerActions.register(actionTypes, activation);
        PuzzleActions.register(actionTypes, puzzles);
        SignalAction.register(actionTypes, settings.signals());

        if (settings.flushIntervalMillis() > 0) {
            this.writer = Executors.newSingleThreadScheduledExecutor(runnable -> {
                Thread thread = new Thread(runnable, "MysticQuests-NarrativeWriter");
                thread.setDaemon(true);
                return thread;
            });
            long interval = Math.max(100L, settings.flushIntervalMillis());
            writer.scheduleWithFixedDelay(this::flushQuietly, interval, interval, TimeUnit.MILLISECONDS);
        } else {
            this.writer = null;
        }
    }

    // --- Content ---

    /**
     * Compiles a release and, when it has no errors, installs it. On errors nothing changes and the
     * previous release keeps running.
     *
     * @return the compile report; check {@link DiagnosticReport#hasErrors()}
     */
    public DiagnosticReport reload(Map<String, JsonNode> sections, Map<String, String> packageVersions) {
        DiagnosticReport report = new DiagnosticReport();
        NarrativeContent compiled = compiler().compile(sections, packageVersions, report);
        if (!report.hasErrors()) {
            install(compiled);
        }
        return report;
    }

    /** Compiles without installing; used to validate before an atomic reload of all content. */
    public NarrativeContent compile(Map<String, JsonNode> sections, Map<String, String> packageVersions, DiagnosticReport report) {
        return compiler().compile(sections, packageVersions, report);
    }

    public void install(NarrativeContent compiled) {
        content.set(compiled);
        overlays.rebind();
    }

    private NarrativeContentCompiler compiler() {
        return new NarrativeContentCompiler(settings.scopes(), conditionTypes, actionTypes, settings.openNamespaces(),
                media.catalog(), media.fallbackLocale());
    }

    public NarrativeContent content() {
        return content.get();
    }

    // --- Player lifecycle ---

    /**
     * Restores a joining player's sessions (their own and their party's), prefetches their state,
     * and retries puzzle outputs that could not finish while they were away (§22).
     */
    public void onJoin(UUID player) {
        for (SessionOwner owner : audiences.owners(player)) {
            sessions.load(owner);
        }
        store.state(ScopeOwner.player(player));
        puzzles.resumePending(player);
        cutscenes.recover(player);
    }

    /**
     * Saves and unloads a leaving player's own sessions and state. Party sessions are released only
     * when {@code partyStillOnline} is false, because other members are still playing them.
     */
    public void onQuit(UUID player, Optional<String> party, boolean partyStillOnline) {
        media.onQuit(player);
        sessions.release(SessionOwner.player(player));
        if (party.isPresent() && !partyStillOnline) {
            sessions.release(SessionOwner.party(party.get()));
        }
        String id = player.toString();
        store.evict(owner -> switch (owner.scope()) {
            case PLAYER, TEMPORARY -> owner.ownerId().equals(id);
            case QUEST -> owner.ownerId().startsWith(id + "|");
            default -> false;
        });
    }

    /** Applies the configured party exit policy for a member who left. */
    public void onPartyMemberLeft(String partyId, UUID player) {
        sessions.onMemberLeft(partyId, player, settings.partyExitPolicy());
    }

    public void onPartyDisbanded(String partyId, Collection<UUID> members) {
        sessions.onPartyDisbanded(partyId, members, settings.partyExitPolicy());
    }

    private List<String> activeSessionIds(UUID player) {
        List<String> ids = new ArrayList<>(2);
        for (SessionOwner owner : audiences.owners(player)) {
            for (QuestSession session : sessions.load(owner)) {
                if (session.active()) {
                    ids.add(session.id());
                }
            }
        }
        return ids;
    }

    // --- Persistence ---

    /** Writes everything pending, on the calling thread. Also purges expired tags. */
    public void flush() {
        store.purgeExpired(settings.clock().instant());
        store.flush();
        sessions.flush();
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException failure) {
            settings.problems().accept("narrative writer tick failed: " + failure);
        }
    }

    @Override
    public void close() {
        if (writer != null) {
            writer.shutdown();
            try {
                if (!writer.awaitTermination(5, TimeUnit.SECONDS)) {
                    writer.shutdownNow();
                }
            } catch (InterruptedException interrupted) {
                writer.shutdownNow();
                Thread.currentThread().interrupt();
            }
        }
        flush();
    }

    // --- Services ---

    public QuestTagService tags() {
        return tags;
    }

    public QuestVariableService variables() {
        return variables;
    }

    public QuestSessionService sessions() {
        return sessions;
    }

    public NarrativeStateStore store() {
        return store;
    }

    public ConditionTypeRegistry conditionTypes() {
        return conditionTypes;
    }

    public ActionTypeRegistry actionTypes() {
        return actionTypes;
    }

    public ConditionEvaluator conditions() {
        return conditions;
    }

    public ActionExecutor executor() {
        return executor;
    }

    public AudienceResolver audiences() {
        return audiences;
    }

    public TriggerActivationService activation() {
        return activation;
    }

    public QuestPuzzleService puzzles() {
        return puzzles;
    }

    public QuestTriggerService triggers() {
        return triggers;
    }

    public ScopeResolver resolver() {
        return resolver;
    }

    public NarrativeAudit audit() {
        return audit;
    }

    /** Story audio: who hears which line, channel queues, and per-listener music (§13). */
    public QuestMediaService media() {
        return media;
    }

    /**
     * Rewinds the first of a player's active sessions that holds a checkpoint named {@code label}
     * (§21.1). Presentation follows by itself: overlays, music and entity visibility are derived
     * from the restored state, and a cutscene restored mid-run is finished when its player rejoins.
     *
     * @return the rewound session's id, or empty when no active session has that checkpoint
     */
    public Optional<String> rewind(UUID player, String label) {
        for (SessionOwner owner : audiences.owners(player)) {
            for (QuestSession session : sessions.load(owner)) {
                if (!session.active()) {
                    continue;
                }
                List<String> problems = new ArrayList<>();
                if (session.rollback(label, problems)) {
                    problems.forEach(problem -> settings.problems().accept("rewind of " + session.id() + ": " + problem));
                    return Optional.of(session.id());
                }
            }
        }
        return Optional.empty();
    }

    /**
     * Restarts a story for a player's audience (§21.1 "Restart node / act"): the active session is
     * abandoned, so the next interaction opens a fresh one with new puzzle selections and no
     * session-scoped state. The old session stays on disk for inspection. Story entities it claimed
     * stay isolated to the same audience; content that should remove them despawns them itself.
     *
     * @return the abandoned session's id, or empty when the story is not active for them
     */
    public Optional<String> restartStory(UUID player, String storyKey) {
        for (SessionOwner owner : audiences.owners(player)) {
            Optional<QuestSession> session = sessions.active(owner, storyKey);
            if (session.isPresent()) {
                sessions.abandon(session.get());
                return Optional.of(session.get().id());
            }
        }
        return Optional.empty();
    }

    /** Everything held about one player, read-only, for {@code /mq debug} and diagnostic exports (§21). */
    public NarrativeDebug debug() {
        return new NarrativeDebug(this, settings.serverId());
    }

    /** Cutscene timelines and their skip and recovery contract (§17). */
    public QuestCutsceneService cutscenes() {
        return cutscenes;
    }

    /** Which live entities belong to which story session; read by the entity isolation systems. */
    public StoryEntityRegistry storyEntities() {
        return storyEntities;
    }

    /** Per-audience overlay entities (§9); a presentation layer like {@link #storyEntities()}. */
    public WorldOverlayRegistry overlays() {
        return overlays;
    }

    /** Every layer that binds entities by durable identity; fed by the entity index each tick. */
    public List<PresentationLayer> observedLayers() {
        return List.of(storyEntities, overlays);
    }

    public String serverId() {
        return settings.serverId();
    }
}
