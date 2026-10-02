package org.hyzionstudios.mysticquests.narrative.cutscene;

import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionExecutor;
import org.hyzionstudios.mysticquests.narrative.action.TransitionReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService;
import org.hyzionstudios.mysticquests.narrative.session.AudienceResolver;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Plays cutscene timelines inside story sessions (§17).
 *
 * <p>Each step is its own ledger transition keyed by the play's run id, so a step runs exactly once
 * per play even when the scene is advanced, skipped and recovered in any order. However a scene
 * ends, it ends the same way (§17.1): remaining required steps run in order, cosmetic ones are
 * dropped, queued story audio for the audience is stopped, and {@code onEnd} runs.
 *
 * <ul>
 *   <li><b>Played out:</b> every step ran at its time, then {@code onEnd}.</li>
 *   <li><b>Skipped:</b> by the player when the scene allows it, or by staff.</li>
 *   <li><b>Replaced:</b> starting another scene in the same session finishes the first as skipped.</li>
 *   <li><b>Recovered:</b> a scene found when its player joins (they disconnected, or the server
 *       restarted mid-scene) is finished as skipped, never resumed half-way.</li>
 * </ul>
 *
 * <p>A scene advances on its starting player's world thread, because its steps may include v1
 * events that touch that player. Other members of a party session hear its audio but do not
 * drive it.
 */
public final class QuestCutsceneService {
    static final String COMPONENT = "cutscene";

    public enum Outcome {
        STARTED, FINISHED, SKIPPED, PENDING, UNKNOWN_CUTSCENE, NO_AUDIENCE, NOT_RUNNING, NOT_SKIPPABLE
    }

    private final Supplier<Map<NamespacedId, CutsceneDefinition>> cutscenes;
    private final Function<String, String> contentVersions;
    private final QuestSessionService sessions;
    private final AudienceResolver audiences;
    private final ActionExecutor executor;
    private final QuestMediaService media;
    private final Clock clock;
    private final Consumer<String> problems;
    /** Session id to the player who drives its running scene; lets the ticker skip idle players. */
    private final Map<String, UUID> running = new ConcurrentHashMap<>();

    public QuestCutsceneService(Supplier<Map<NamespacedId, CutsceneDefinition>> cutscenes,
                                Function<String, String> contentVersions, QuestSessionService sessions,
                                AudienceResolver audiences, ActionExecutor executor, QuestMediaService media,
                                Clock clock, Consumer<String> problems) {
        this.cutscenes = cutscenes;
        this.contentVersions = contentVersions;
        this.sessions = sessions;
        this.audiences = audiences;
        this.executor = executor;
        this.media = media;
        this.clock = clock;
        this.problems = problems;
    }

    /**
     * Starts a scene for a player's audience. Steps at time zero run before this returns.
     *
     * @param sessionId the session to play in, typically the one whose transition started the scene;
     *         null (or a session of another story) opens the scene's own story session
     */
    public Outcome play(UUID actor, NamespacedId cutsceneId, @Nullable String sessionId) {
        CutsceneDefinition definition = cutscenes.get().get(cutsceneId);
        if (definition == null) {
            return Outcome.UNKNOWN_CUTSCENE;
        }
        QuestSession session = sessionId == null ? null : sessions.get(sessionId)
                .filter(candidate -> candidate.storyKey().equals(definition.story())).orElse(null);
        if (session == null) {
            SessionOwner owner = audiences.owner(actor, definition.audience());
            if (owner == null) {
                return Outcome.NO_AUDIENCE;
            }
            session = sessions.open(owner, definition.story(), contentVersions.apply(definition.packageId()));
        }
        synchronized (session) {
            Optional<CutsceneRun> previous = run(session);
            if (previous.isPresent() && finish(session, previous.get(), true) == Outcome.PENDING) {
                problems.accept("cutscene " + cutsceneId + " waits: the previous scene in session " + session.id()
                        + " could not finish its required steps");
                return Outcome.PENDING;
            }
            CutsceneRun run = new CutsceneRun(cutsceneId, UUID.randomUUID().toString(), clock.instant(), actor);
            session.putComponent(COMPONENT, run);
            running.put(session.id(), actor);
            Outcome advanced = advance(session, run, definition);
            return advanced == Outcome.FINISHED ? Outcome.FINISHED : Outcome.STARTED;
        }
    }

    /**
     * Ends the scene a player is watching early.
     *
     * @param force staff override of {@code skippable: false}
     */
    public Outcome skip(UUID player, boolean force) {
        Outcome outcome = Outcome.NOT_RUNNING;
        for (QuestSession session : activeSessions(player)) {
            synchronized (session) {
                Optional<CutsceneRun> run = run(session);
                if (run.isEmpty()) {
                    continue;
                }
                CutsceneDefinition definition = cutscenes.get().get(run.get().cutscene());
                if (definition != null && !definition.skippable() && !force) {
                    outcome = Outcome.NOT_SKIPPABLE;
                    continue;
                }
                return finish(session, run.get(), true);
            }
        }
        return outcome;
    }

    /** Runs the steps that are due in scenes this player drives. Called on their world thread. */
    public void advance(UUID player) {
        Set<String> driven = new HashSet<>();
        for (QuestSession session : activeSessions(player)) {
            synchronized (session) {
                Optional<CutsceneRun> run = run(session);
                if (run.isEmpty() || !run.get().actor().equals(player)) {
                    continue;
                }
                driven.add(session.id());
                CutsceneDefinition definition = cutscenes.get().get(run.get().cutscene());
                if (definition == null) {
                    problems.accept("cutscene " + run.get().cutscene() + " is no longer in the content; dropping its run in session "
                            + session.id());
                    session.removeComponent(COMPONENT);
                    running.remove(session.id());
                    continue;
                }
                advance(session, run.get(), definition);
            }
        }
        // Sessions abandoned, completed or rewound past their scene stop being scheduled.
        running.entrySet().removeIf(entry -> entry.getValue().equals(player) && !driven.contains(entry.getKey()));
    }

    /** The players driving a scene right now, so the engine only schedules work for them. */
    public Set<UUID> drivers() {
        return Set.copyOf(running.values());
    }

    /**
     * Finishes, as skipped, any scene this player was driving when they left or the server stopped.
     * Called when they join. Resuming half-way would replay the opening on a camera that has moved on.
     */
    public void recover(UUID player) {
        for (QuestSession session : activeSessions(player)) {
            synchronized (session) {
                Optional<CutsceneRun> run = run(session);
                if (run.isPresent() && run.get().actor().equals(player)) {
                    running.put(session.id(), player);
                    finish(session, run.get(), true);
                }
            }
        }
    }

    /** What a player is watching, for the debugger. */
    public List<String> describe(UUID player) {
        List<String> lines = new ArrayList<>();
        Instant now = clock.instant();
        for (QuestSession session : activeSessions(player)) {
            synchronized (session) {
                run(session).ifPresent(run -> {
                    CutsceneDefinition definition = cutscenes.get().get(run.cutscene());
                    lines.add(run.cutscene() + " in " + session.id() + ": step " + run.next() + "/"
                            + (definition == null ? "?" : definition.steps().size())
                            + ", " + Duration.between(run.startedAt(), now).toMillis() + "ms in"
                            + (run.skipping() ? ", finishing" : "") + ", driven by " + run.actor());
                });
            }
        }
        return lines;
    }

    // --- Internals; callers hold the session monitor ---

    private Outcome advance(QuestSession session, CutsceneRun run, CutsceneDefinition definition) {
        long elapsed = Duration.between(run.startedAt(), clock.instant()).toMillis();
        ActionContext context = context(session, run);
        List<CutsceneDefinition.Step> steps = definition.steps();
        while (run.next() < steps.size()) {
            CutsceneDefinition.Step step = steps.get(run.next());
            if (!run.skipping() && step.atMillis() > elapsed) {
                return Outcome.STARTED;
            }
            if (!(run.skipping() && step.cosmetic())) {
                TransitionReport report = executor.run(stepKey(run, step.key()), List.of(step.action()), context, session);
                if (!report.complete() && !step.cosmetic()) {
                    return Outcome.PENDING;
                }
            }
            run.advanceTo(run.next() + 1);
            session.markChanged();
        }
        TransitionReport end = executor.run(stepKey(run, "end"), definition.onEnd(), context, session);
        if (!end.complete()) {
            return Outcome.PENDING;
        }
        session.removeComponent(COMPONENT);
        running.remove(session.id());
        return run.skipping() ? Outcome.SKIPPED : Outcome.FINISHED;
    }

    private Outcome finish(QuestSession session, CutsceneRun run, boolean skipped) {
        CutsceneDefinition definition = cutscenes.get().get(run.cutscene());
        if (definition == null) {
            session.removeComponent(COMPONENT);
            running.remove(session.id());
            return Outcome.NOT_RUNNING;
        }
        if (skipped && !run.skipping()) {
            run.markSkipping();
            session.markChanged();
            // Scene-only lines still queued for the audience would otherwise play after the scene.
            media.stop(media.listeners(QuestMediaService.Audience.STORY_SESSION, context(session, run).scope()), null);
        }
        return advance(session, run, definition);
    }

    private ActionContext context(QuestSession session, CutsceneRun run) {
        return new ActionContext(audiences.context(run.actor(), session, null), "cutscene:" + run.cutscene());
    }

    private static String stepKey(CutsceneRun run, String step) {
        return "cutscene:" + run.cutscene() + ":" + run.runId() + ":" + step;
    }

    private Optional<CutsceneRun> run(QuestSession session) {
        try {
            return session.existingComponent(COMPONENT, CutsceneRun.class, CutsceneRun::fromJson);
        } catch (RuntimeException unreadable) {
            problems.accept("cutscene state in session " + session.id() + " is unreadable; dropping it: " + unreadable);
            session.removeComponent(COMPONENT);
            return Optional.empty();
        }
    }

    private List<QuestSession> activeSessions(UUID player) {
        List<QuestSession> active = new ArrayList<>(2);
        for (SessionOwner owner : audiences.owners(player)) {
            for (QuestSession session : sessions.load(owner)) {
                if (session.active()) {
                    active.add(session);
                }
            }
        }
        return active;
    }
}
