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
import org.hyzionstudios.mysticquests.narrative.overlay.WorldOverlayRegistry;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * World overlay actions (§9, §10.2 "apply a world overlay or open/close a door").
 *
 * <pre>
 *   { "type": "mysticquests:overlay.hide",  "overlay": "hyzion:druid_temple.seal" }
 *   { "type": "mysticquests:overlay.show",  "overlay": "hyzion:druid_temple.bridge", "scope": "party" }
 *   { "type": "mysticquests:overlay.reset", "overlay": "hyzion:druid_temple.seal" }
 * </pre>
 *
 * <p>{@code scope} works as for trigger actions: the session by default when the action runs inside
 * one, otherwise the player, and global only when written. Whether the overlay exists is checked
 * after every overlay in the reload has compiled.
 */
public final class OverlayActions {
    public static final NamespacedId SHOW = NamespacedId.of("mysticquests", "overlay.show");
    public static final NamespacedId HIDE = NamespacedId.of("mysticquests", "overlay.hide");
    public static final NamespacedId RESET = NamespacedId.of("mysticquests", "overlay.reset");

    private OverlayActions() {
    }

    public static void register(ActionTypeRegistry registry, TriggerActivationService activation) {
        registry.registerBuiltIn(SHOW, new Change(activation, Boolean.TRUE));
        registry.registerBuiltIn(HIDE, new Change(activation, Boolean.FALSE));
        registry.registerBuiltIn(RESET, new Change(activation, null));
    }

    private record Change(TriggerActivationService activation, Boolean present) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            TriggerScope scope = TriggerScope.parse(parameters.path("scope").asText(null));
            if (scope == null) {
                scope = context.scope().sessionId() != null ? TriggerScope.STORY_SESSION : TriggerScope.PLAYER;
            }
            String key = WorldOverlayRegistry.key(ContentParams.id(parameters, "overlay"));
            return ActionResult.of(activation.set(scope, key, present, context.scope()));
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            ContentParams.id(parameters, "overlay", path, report);
            if (parameters.hasNonNull("scope") && TriggerScope.parse(parameters.get("scope").asText()) == null) {
                report.error(DiagnosticCode.INVALID_SCOPE, path, "unknown overlay scope '" + parameters.get("scope").asText() + "'",
                        "use global, party, player or session");
            }
        }
    }
}
