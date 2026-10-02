package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.InputResult;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;

/**
 * Puzzle actions, so any transition — a quest step, another puzzle's output, a conversation — can
 * drive a puzzle.
 *
 * <pre>
 *   { "type": "mysticquests:puzzle.input", "puzzle": "hyzion:druid_temple.hidden_keys", "input": "key_3" }
 *   { "type": "mysticquests:puzzle.reset", "puzzle": "hyzion:fire_trial.switches", "reroll": false }
 * </pre>
 *
 * <p>Whether the named puzzle exists is checked after every puzzle in the reload has compiled,
 * because an action may name a puzzle that is defined later in the same content set.
 */
public final class PuzzleActions {
    public static final NamespacedId INPUT = NamespacedId.of("mysticquests", "puzzle.input");
    public static final NamespacedId RESET = NamespacedId.of("mysticquests", "puzzle.reset");

    /** Input outcomes that are a normal "nothing to do", as opposed to a misconfiguration. */
    private static final Set<Outcome> HARMLESS = EnumSet.of(
            Outcome.IGNORED_INACTIVE, Outcome.IGNORED_DUPLICATE, Outcome.IGNORED_COMPLETED,
            Outcome.IGNORED_NO_TRANSITION, Outcome.BLOCKED, Outcome.MISTAKE);

    private PuzzleActions() {
    }

    public static void register(ActionTypeRegistry registry, QuestPuzzleService puzzles) {
        registry.registerBuiltIn(INPUT, new Input(puzzles));
        registry.registerBuiltIn(RESET, new Reset(puzzles));
    }

    private record Input(QuestPuzzleService puzzles) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            UUID actor = context.scope().actor();
            if (actor == null) {
                return ActionResult.terminal("puzzle.input needs an acting player");
            }
            InputResult result = puzzles.input(actor, context.scope().world(), ContentParams.id(parameters, "puzzle"),
                    parameters.path("input").asText(), parameters.path("activate").asBoolean(true));
            return outcome(result);
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            ContentParams.id(parameters, "puzzle", path, report);
            if (parameters.path("input").asText("").isBlank()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "puzzle.input needs an \"input\"");
            }
        }
    }

    private record Reset(QuestPuzzleService puzzles) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            UUID actor = context.scope().actor();
            if (actor == null) {
                return ActionResult.terminal("puzzle.reset needs an acting player");
            }
            return outcome(puzzles.reset(actor, ContentParams.id(parameters, "puzzle"),
                    parameters.path("reroll").asBoolean(false), context.source()));
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            ContentParams.id(parameters, "puzzle", path, report);
        }
    }

    private static ActionResult outcome(InputResult result) {
        return switch (result.outcome()) {
            case ACCEPTED, COMPLETED, DEACTIVATED -> ActionResult.success();
            case NO_AUDIENCE -> ActionResult.retryable(result.message());
            case UNKNOWN_PUZZLE, UNKNOWN_INPUT -> ActionResult.terminal(result.message());
            default -> HARMLESS.contains(result.outcome())
                    ? ActionResult.skipped(result.outcome() + ": " + result.message())
                    : ActionResult.terminal(result.message());
        };
    }
}
