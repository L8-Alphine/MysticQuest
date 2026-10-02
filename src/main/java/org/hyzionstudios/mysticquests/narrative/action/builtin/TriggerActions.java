package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;

/**
 * Logical trigger-volume actions.
 *
 * <pre>
 *   { "type": "mysticquests:trigger.disable", "volume": "avalon:key_search_3", "scope": "session" }
 *   { "type": "mysticquests:trigger.enable",  "volumes": ["avalon:gate_a", "avalon:gate_b"], "scope": "player" }
 *   { "type": "mysticquests:trigger.clear",   "volume": "avalon:key_search_3", "scope": "session" }
 * </pre>
 *
 * <p>{@code scope} defaults to the session when the action runs inside one, otherwise to the acting
 * player. It is never global by default: a global change is a deliberate, server-wide act and must
 * be written out.
 */
public final class TriggerActions {
    public static final NamespacedId ENABLE = NamespacedId.of("mysticquests", "trigger.enable");
    public static final NamespacedId DISABLE = NamespacedId.of("mysticquests", "trigger.disable");
    public static final NamespacedId CLEAR = NamespacedId.of("mysticquests", "trigger.clear");

    private TriggerActions() {
    }

    public static void register(ActionTypeRegistry registry, TriggerActivationService triggers) {
        registry.registerBuiltIn(ENABLE, new Toggle(triggers, Boolean.TRUE));
        registry.registerBuiltIn(DISABLE, new Toggle(triggers, Boolean.FALSE));
        registry.registerBuiltIn(CLEAR, new Toggle(triggers, null));
    }

    private record Toggle(TriggerActivationService triggers, Boolean enabled) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            TriggerScope scope = TriggerScope.parse(parameters.path("scope").asText(null));
            if (scope == null) {
                scope = context.scope().sessionId() != null ? TriggerScope.STORY_SESSION : TriggerScope.PLAYER;
            }
            for (String volume : volumes(parameters)) {
                ActionResult result = ActionResult.of(triggers.set(scope, volume, enabled, context.scope()));
                if (!result.status().settled()) {
                    return result;
                }
            }
            return ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            if (volumes(parameters).isEmpty()) {
                report.error(DiagnosticCode.UNKNOWN_TRIGGER_VOLUME, path,
                        "names no trigger volume", "set \"volume\": \"world:volumeId\" or a \"volumes\" array");
            }
            if (parameters.hasNonNull("scope") && TriggerScope.parse(parameters.get("scope").asText()) == null) {
                report.error(DiagnosticCode.INVALID_SCOPE, path,
                        "unknown trigger scope '" + parameters.get("scope").asText() + "'",
                        "use global, party, player or session");
            }
        }

        private static List<String> volumes(JsonNode parameters) {
            List<String> volumes = new ArrayList<>();
            String single = parameters.path("volume").asText("");
            if (!single.isBlank()) {
                volumes.add(single.trim());
            }
            for (JsonNode volume : parameters.path("volumes")) {
                if (!volume.asText("").isBlank()) {
                    volumes.add(volume.asText().trim());
                }
            }
            return volumes;
        }
    }
}
