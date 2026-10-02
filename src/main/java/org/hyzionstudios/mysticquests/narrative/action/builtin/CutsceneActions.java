package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.cutscene.QuestCutsceneService;
import org.hyzionstudios.mysticquests.narrative.cutscene.QuestCutsceneService.Outcome;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Cutscene actions (§17).
 *
 * <pre>
 *   { "type": "mysticquests:cutscene.play", "cutscene": "hyzion:druid_temple.awakening" }
 *   { "type": "mysticquests:cutscene.skip" }
 * </pre>
 *
 * <p>A scene started from a transition plays in that transition's session when it belongs to the
 * same story, and otherwise opens its own. Whether the cutscene exists is checked after all
 * cutscenes in the reload have compiled.
 */
public final class CutsceneActions {
    public static final NamespacedId PLAY = NamespacedId.of("mysticquests", "cutscene.play");
    public static final NamespacedId SKIP = NamespacedId.of("mysticquests", "cutscene.skip");

    private CutsceneActions() {
    }

    public static void register(ActionTypeRegistry registry, QuestCutsceneService cutscenes) {
        registry.registerBuiltIn(PLAY, new Play(cutscenes));
        registry.registerBuiltIn(SKIP, new Skip(cutscenes));
    }

    private record Play(QuestCutsceneService cutscenes) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            UUID actor = context.scope().actor();
            if (actor == null) {
                return ActionResult.terminal("cutscene.play needs a player to show the scene to");
            }
            Outcome outcome = cutscenes.play(actor, ContentParams.id(parameters, "cutscene"), context.scope().sessionId());
            return switch (outcome) {
                case STARTED, FINISHED -> ActionResult.success();
                case PENDING -> ActionResult.retryable("the previous scene has not finished its required steps");
                case NO_AUDIENCE -> ActionResult.skipped("the scene needs a party, and the player is not in one");
                default -> ActionResult.terminal("cutscene.play: " + outcome);
            };
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            ContentParams.id(parameters, "cutscene", path, report);
        }
    }

    private record Skip(QuestCutsceneService cutscenes) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            UUID actor = context.scope().actor();
            if (actor == null) {
                return ActionResult.skipped("cutscene.skip has no player");
            }
            Outcome outcome = cutscenes.skip(actor, parameters.path("force").asBoolean(false));
            return switch (outcome) {
                case SKIPPED, FINISHED -> ActionResult.success();
                case PENDING -> ActionResult.retryable("the scene could not finish its required steps yet");
                default -> ActionResult.skipped("cutscene.skip: " + outcome);
            };
        }
    }
}
