package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Counter;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.action.ActionExecutor;
import org.hyzionstudios.mysticquests.narrative.action.TransitionReport;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionEvaluator;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Input;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.MachineState;
import org.hyzionstudios.mysticquests.narrative.session.AudienceResolver;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * Runs puzzles against sessions (§10, QuestPuzzleService).
 *
 * <p>A puzzle's state is a component of the audience's session, so it is scoped, persisted,
 * transferred and checkpointed with it. Inputs are processed under the session's monitor, so two
 * party members stepping on plates in the same tick cannot interleave a read-modify-write. Actions
 * run under that monitor too. An output that feeds a puzzle in a <em>different</em> session takes
 * that session's monitor as well, so content must not make two sessions' puzzles feed each other.
 *
 * <h2>Exactly-once outputs</h2>
 *
 * <p>Completion runs {@code outputs} as a transition keyed by puzzle and round. If an output fails
 * (say, a reward needs the player online), the puzzle stays complete and the transition stays
 * incomplete. The next input, or the player's next join, retries it, and the ledger skips every
 * output that already ran. Outputs fire once per round, whatever happens in between.
 */
public final class QuestPuzzleService {
    public enum Outcome {
        /** The input counted. */
        ACCEPTED,
        /** The input counted and finished the puzzle. */
        COMPLETED,
        /** A toggleable input was released. */
        DEACTIVATED,
        /** Not one of this audience's active inputs: an unselected key does nothing (§26.3). */
        IGNORED_INACTIVE,
        /** Already activated this round. */
        IGNORED_DUPLICATE,
        /** The puzzle is already solved and is not repeatable. */
        IGNORED_COMPLETED,
        /** A state machine has no transition on this input from its current state. */
        IGNORED_NO_TRANSITION,
        /** The puzzle's {@code requires} condition does not hold. */
        BLOCKED,
        /** Wrong input in a sequence. */
        MISTAKE,
        /** Party audience, but the player is not in a party. */
        NO_AUDIENCE,
        UNKNOWN_PUZZLE,
        UNKNOWN_INPUT
    }

    /** @param transition the outputs or reset transition this input ran, if any */
    public record InputResult(Outcome outcome, String message, @Nullable String sessionId, @Nullable TransitionReport transition) {
        static InputResult of(Outcome outcome, String message) {
            return new InputResult(outcome, message, null, null);
        }
    }

    /** Whether an input currently matters to an audience, for world presentation and volume gating. */
    public enum InputStatus { UNKNOWN, INACTIVE, AVAILABLE, ACTIVATED, SOLVED }

    /** A read-only snapshot for the debugger. */
    public record PuzzleView(NamespacedId puzzle, String sessionId, SessionOwner owner, int generation, long seed,
                             List<String> activeInputs, Map<String, Instant> activated, List<String> sequenceProgress,
                             @Nullable String machineState, boolean completed, boolean outputsComplete) {
    }

    private final Supplier<Map<NamespacedId, PuzzleDefinition>> puzzles;
    private final Function<String, String> contentVersions;
    private final QuestSessionService sessions;
    private final AudienceResolver audiences;
    private final ActionExecutor executor;
    private final ConditionEvaluator conditions;
    private final Clock clock;
    private final Consumer<String> problems;
    private final NarrativeMetrics metrics;

    /**
     * @param contentVersions the release version of a content package, stamped onto sessions it opens
     */
    public QuestPuzzleService(
            Supplier<Map<NamespacedId, PuzzleDefinition>> puzzles,
            Function<String, String> contentVersions,
            QuestSessionService sessions,
            AudienceResolver audiences,
            ActionExecutor executor,
            ConditionEvaluator conditions,
            Clock clock,
            Consumer<String> problems,
            NarrativeMetrics metrics) {
        this.metrics = metrics;
        this.puzzles = puzzles;
        this.contentVersions = contentVersions;
        this.sessions = sessions;
        this.audiences = audiences;
        this.executor = executor;
        this.conditions = conditions;
        this.clock = clock;
        this.problems = problems;
    }

    // --- Input ---

    /**
     * Feeds one input event to a puzzle.
     *
     * @param activate true for activation; false releases a toggleable input
     * @param world the player's world, for world-scoped state the puzzle's actions touch; may be null
     */
    public InputResult input(UUID player, @Nullable String world, NamespacedId puzzleId, String inputId, boolean activate) {
        PuzzleDefinition definition = puzzles.get().get(puzzleId);
        if (definition == null) {
            return InputResult.of(Outcome.UNKNOWN_PUZZLE, "no puzzle " + puzzleId);
        }
        Optional<Input> input = definition.input(inputId);
        if (input.isEmpty()) {
            return InputResult.of(Outcome.UNKNOWN_INPUT, "puzzle " + puzzleId + " has no input '" + inputId + "'");
        }
        QuestSession session = session(player, definition);
        if (session == null) {
            return InputResult.of(Outcome.NO_AUDIENCE, "puzzle " + puzzleId + " needs a party, and the player is not in one");
        }
        ScopeContext scope = audiences.context(player, session, world);
        ActionContext context = new ActionContext(scope, source(definition));
        synchronized (session) {
            PuzzleState state = state(session, definition);
            TransitionReport retried = retryOutputs(session, definition, state, context);
            if (state.completed()) {
                return result(Outcome.IGNORED_COMPLETED, "already solved", session, retried);
            }
            if (!PuzzleRules.activeInputs(definition, state).contains(inputId)) {
                return result(Outcome.IGNORED_INACTIVE, "'" + inputId + "' is not active for " + session.owner(), session, null);
            }
            if (definition.requires() != null && !conditions.test(definition.requires(), scope)) {
                return result(Outcome.BLOCKED, "requirements not met", session, null);
            }
            if (!activate) {
                return release(session, definition, state, input.get(), context);
            }
            Outcome rejected = apply(session, definition, state, inputId, context);
            if (rejected != null) {
                if (rejected == Outcome.MISTAKE) {
                    metrics.increment(Counter.PUZZLE_MISTAKES);
                }
                return result(rejected, rejected == Outcome.MISTAKE ? "wrong input; progress reset" : "", session, null);
            }
            int accepted = state.nextAccepted();
            session.markChanged();
            run(definition.onInput(), key(definition, state, "input" + accepted), session, context);
            return completeIfSolved(session, definition, state, context);
        }
    }

    /**
     * Applies an activation according to the rule.
     *
     * @return the outcome when the input is refused, or null when it was applied
     */
    @Nullable
    private Outcome apply(QuestSession session, PuzzleDefinition definition, PuzzleState state, String inputId, ActionContext context) {
        Instant now = clock.instant();
        PuzzleDefinition.Rule rule = definition.rule();
        switch (rule.type()) {
            case SEQUENCE -> {
                List<String> progress = state.sequenceProgress();
                if (progress.contains(inputId)) {
                    return Outcome.IGNORED_DUPLICATE;
                }
                if (progress.size() < rule.sequence().size() && rule.sequence().get(progress.size()).equals(inputId)) {
                    state.advanceSequence(inputId);
                    state.activate(inputId, now);
                    return null;
                }
                int mistake = state.nextMistake();
                if (rule.resetOnMistake()) {
                    state.clearProgress();
                }
                session.markChanged();
                run(definition.onMistake(), key(definition, state, "mistake" + mistake), session, context);
                return Outcome.MISTAKE;
            }
            case STATE_MACHINE -> {
                PuzzleDefinition.StateMachine machine = rule.machine();
                MachineState current = machine.states().get(state.machineState());
                String target = current == null ? null : current.transitions().get(inputId);
                if (target == null) {
                    return Outcome.IGNORED_NO_TRANSITION;
                }
                state.setMachineState(target);
                state.activate(inputId, now);
                session.markChanged();
                run(machine.states().get(target).onEnter(),
                        key(definition, state, "enter" + (state.accepted() + 1) + ":" + target), session, context);
                return null;
            }
            case TIMED -> {
                state.expireBefore(now.minus(rule.window()));
                if (state.activated().containsKey(inputId)) {
                    return Outcome.IGNORED_DUPLICATE;
                }
                state.activate(inputId, now);
                return null;
            }
            default -> {
                if (state.activated().containsKey(inputId)) {
                    return Outcome.IGNORED_DUPLICATE;
                }
                state.activate(inputId, now);
                return null;
            }
        }
    }

    private InputResult release(QuestSession session, PuzzleDefinition definition, PuzzleState state, Input input, ActionContext context) {
        if (!input.toggleable()) {
            return result(Outcome.IGNORED_DUPLICATE, "'" + input.id() + "' cannot be released", session, null);
        }
        if (!state.deactivate(input.id())) {
            return result(Outcome.IGNORED_DUPLICATE, "'" + input.id() + "' was not active", session, null);
        }
        session.markChanged();
        InputResult completed = completeIfSolved(session, definition, state, context);
        return completed.outcome() == Outcome.COMPLETED ? completed : result(Outcome.DEACTIVATED, "", session, null);
    }

    private InputResult completeIfSolved(QuestSession session, PuzzleDefinition definition, PuzzleState state, ActionContext context) {
        if (!PuzzleRules.complete(definition, state)) {
            return result(Outcome.ACCEPTED, "", session, null);
        }
        state.complete(clock.instant());
        session.markChanged();
        TransitionReport outputs = run(definition.outputs(), completionKey(definition, state), session, context);
        if (definition.repeatable() && outputs.complete()) {
            state.newRound(initialState(definition));
            metrics.increment(Counter.PUZZLE_RESETS);
            session.markChanged();
        }
        return result(Outcome.COMPLETED, "solved", session, outputs);
    }

    // --- Reset and recovery ---

    /**
     * Starts a new round: progress cleared, generation advanced, outputs re-armed. The selection is
     * kept unless {@code reroll}; rerolling draws a fresh, persisted selection for the new round.
     */
    public InputResult reset(UUID player, NamespacedId puzzleId, boolean reroll, String source) {
        PuzzleDefinition definition = puzzles.get().get(puzzleId);
        if (definition == null) {
            return InputResult.of(Outcome.UNKNOWN_PUZZLE, "no puzzle " + puzzleId);
        }
        QuestSession session = session(player, definition);
        if (session == null) {
            return InputResult.of(Outcome.NO_AUDIENCE, "puzzle " + puzzleId + " needs a party, and the player is not in one");
        }
        ActionContext context = new ActionContext(audiences.context(player, session, null),
                source == null || source.isBlank() ? source(definition) : source);
        synchronized (session) {
            PuzzleState state = state(session, definition);
            state.newRound(initialState(definition));
            if (reroll && definition.selection() != null) {
                select(session, definition, state);
            }
            session.markChanged();
            TransitionReport report = run(definition.onReset(), key(definition, state, "reset"), session, context);
            return result(Outcome.ACCEPTED, "reset to round " + state.generation(), session, report);
        }
    }

    /**
     * Retries the outputs of every solved puzzle whose outputs did not finish, in the sessions this
     * player takes part in. Called on join, so a reward that failed while the player was offline
     * lands when they return.
     */
    public void resumePending(UUID player) {
        for (SessionOwner owner : audiences.owners(player)) {
            for (QuestSession session : sessions.load(owner)) {
                if (!session.active()) {
                    continue;
                }
                for (PuzzleDefinition definition : puzzles.get().values()) {
                    if (!definition.story().equals(session.storyKey())) {
                        continue;
                    }
                    synchronized (session) {
                        Optional<PuzzleState> state = session.existingComponent(componentKey(definition), PuzzleState.class, PuzzleState::fromJson);
                        if (state.isPresent()) {
                            ActionContext context = new ActionContext(audiences.context(player, session, null), source(definition));
                            retryOutputs(session, definition, state.get(), context);
                        }
                    }
                }
            }
        }
    }

    @Nullable
    private TransitionReport retryOutputs(QuestSession session, PuzzleDefinition definition, PuzzleState state, ActionContext context) {
        if (!state.completed() || session.applied(completionKey(definition, state))) {
            return null;
        }
        TransitionReport report = run(definition.outputs(), completionKey(definition, state), session, context);
        if (definition.repeatable() && report.complete()) {
            state.newRound(initialState(definition));
            session.markChanged();
        }
        return report;
    }

    // --- Queries ---

    /** Whether {@code inputId} matters to this player's audience right now. Opens the session if needed. */
    public InputStatus inputStatus(UUID player, NamespacedId puzzleId, String inputId) {
        PuzzleDefinition definition = puzzles.get().get(puzzleId);
        if (definition == null || definition.input(inputId).isEmpty()) {
            return InputStatus.UNKNOWN;
        }
        QuestSession session = session(player, definition);
        if (session == null) {
            return InputStatus.INACTIVE;
        }
        synchronized (session) {
            PuzzleState state = state(session, definition);
            if (state.completed()) {
                return InputStatus.SOLVED;
            }
            if (!PuzzleRules.activeInputs(definition, state).contains(inputId)) {
                return InputStatus.INACTIVE;
            }
            return state.activated().containsKey(inputId) ? InputStatus.ACTIVATED : InputStatus.AVAILABLE;
        }
    }

    /** A snapshot of a player's audience's state on one puzzle, without creating anything. */
    public Optional<PuzzleView> view(UUID player, NamespacedId puzzleId) {
        PuzzleDefinition definition = puzzles.get().get(puzzleId);
        if (definition == null) {
            return Optional.empty();
        }
        SessionOwner owner = audiences.owner(player, definition.audience());
        if (owner == null) {
            return Optional.empty();
        }
        Optional<QuestSession> session = sessions.active(owner, definition.story());
        if (session.isEmpty()) {
            return Optional.empty();
        }
        synchronized (session.get()) {
            Optional<PuzzleState> state = session.get().existingComponent(componentKey(definition), PuzzleState.class, PuzzleState::fromJson);
            return state.map(value -> new PuzzleView(puzzleId, session.get().id(), session.get().owner(), value.generation(),
                    value.seed(), PuzzleRules.activeInputs(definition, value), value.activated(), value.sequenceProgress(),
                    value.machineState(), value.completed(), session.get().applied(completionKey(definition, value))));
        }
    }

    // --- Internals ---

    @Nullable
    private QuestSession session(UUID player, PuzzleDefinition definition) {
        SessionOwner owner = audiences.owner(player, definition.audience());
        if (owner == null) {
            return null;
        }
        return sessions.open(owner, definition.story(), contentVersions.apply(definition.packageId()));
    }

    /** The audience's state, selecting inputs and entering the initial machine state on first use. */
    private PuzzleState state(QuestSession session, PuzzleDefinition definition) {
        PuzzleState state = session.component(componentKey(definition), PuzzleState.class, PuzzleState::fromJson, PuzzleState::new);
        if (state.selected() == null && definition.selection() != null) {
            select(session, definition, state);
            session.markChanged();
        } else if (state.selected() != null) {
            for (String selected : state.selected()) {
                if (definition.input(selected).isEmpty()) {
                    problems.accept("puzzle " + definition.id() + " in session " + session.id() + " has stored input '"
                            + selected + "', which the current content no longer defines; reset it with reroll to fix");
                }
            }
        }
        if (state.machineState() == null && definition.rule().machine() != null) {
            state.setMachineState(definition.rule().machine().initial());
            session.markChanged();
        }
        return state;
    }

    private void select(QuestSession session, PuzzleDefinition definition, PuzzleState state) {
        long seed = PuzzleSelector.seed(definition.id().toString(), session.id(), state.generation());
        state.select(seed, PuzzleSelector.select(definition.selection().candidates(), definition.selection().active(), seed));
    }

    @Nullable
    private static String initialState(PuzzleDefinition definition) {
        return definition.rule().machine() == null ? null : definition.rule().machine().initial();
    }

    private TransitionReport run(List<ActionDefinition> actions, String key, QuestSession session, ActionContext context) {
        return executor.run(key, actions, context, session);
    }

    private static InputResult result(Outcome outcome, String message, QuestSession session, @Nullable TransitionReport transition) {
        return new InputResult(outcome, message, session.id(), transition);
    }

    static String componentKey(PuzzleDefinition definition) {
        return "puzzle/" + definition.id();
    }

    private static String key(PuzzleDefinition definition, PuzzleState state, String step) {
        return "puzzle:" + definition.id() + ":g" + state.generation() + ":" + step;
    }

    private static String completionKey(PuzzleDefinition definition, PuzzleState state) {
        return key(definition, state, "complete");
    }

    private static String source(PuzzleDefinition definition) {
        return "puzzle:" + definition.id();
    }
}
