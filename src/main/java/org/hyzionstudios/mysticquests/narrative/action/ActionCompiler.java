package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

/**
 * Compiles an authored action list into {@link ActionDefinition}s, validating each one.
 *
 * <pre>
 *   [
 *     { "type": "mysticquests:tag.add", "tag": "hyzion:druid_temple.keys_complete" },
 *     { "type": "mysticquests:trigger.disable", "volume": "avalon:key_search", "scope": "session" },
 *     { "type": "sendTitle", "title": "The way is open", "stepId": "title" }
 *   ]
 * </pre>
 *
 * <p>A type without a namespace is a v1 event ({@code sendTitle}, {@code giveItem}, …) and runs
 * through {@link #LEGACY_EVENT}. Existing content therefore works as puzzle and transition output
 * without being rewritten (§25).
 *
 * <p>{@code stepId} is the optional stable key of an action within its list; see
 * {@link ActionDefinition#key()}. It is a separate field because v1 events already use {@code id}
 * and {@code key} for their own purposes.
 */
public final class ActionCompiler {
    /** The bridge type that runs a v1 event definition. */
    public static final NamespacedId LEGACY_EVENT = NamespacedId.of("mysticquests", "legacy.event");
    public static final String STEP_ID = "stepId";
    /** Overrides {@link ActionHandler#external} for one action. */
    public static final String PERMANENT = "permanent";

    private ActionCompiler() {
    }

    public static List<ActionDefinition> compile(@Nullable JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isObject()) {
            JsonNode wrapped = JsonNodeFactory.instance.arrayNode().add(node);
            return compile(wrapped, path, context, report);
        }
        if (!node.isArray()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "actions must be an array of objects");
            return List.of();
        }
        List<ActionDefinition> actions = new ArrayList<>(node.size());
        Set<String> keys = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            String elementPath = path + "[" + index + "]";
            ActionDefinition action = compileOne(node.get(index), index, elementPath, context, report);
            if (action == null) {
                continue;
            }
            if (!keys.add(action.key())) {
                report.error(DiagnosticCode.DUPLICATE_ID, elementPath,
                        STEP_ID + " '" + action.key() + "' is used twice in one action list");
                continue;
            }
            actions.add(action);
        }
        return actions;
    }

    @Nullable
    private static ActionDefinition compileOne(JsonNode element, int index, String path, CompileContext context, DiagnosticReport report) {
        if (element == null || !element.isObject()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "an action must be a JSON object");
            return null;
        }
        String rawType = element.path("type").asText("").trim();
        if (rawType.isEmpty()) {
            report.error(DiagnosticCode.UNKNOWN_ACTION, path, "action has no \"type\"");
            return null;
        }
        String key = element.hasNonNull(STEP_ID) ? element.get(STEP_ID).asText().trim() : "i" + index;
        if (key.isEmpty()) {
            report.error(DiagnosticCode.INVALID_ID, path, STEP_ID + " must not be blank");
            return null;
        }
        NamespacedId type;
        ObjectNode parameters;
        if (rawType.indexOf(':') >= 0) {
            String problem = NamespacedId.describeProblem(rawType);
            if (problem != null) {
                report.error(DiagnosticCode.INVALID_ID, path, "action type: " + problem);
                return null;
            }
            type = NamespacedId.parse(rawType);
            parameters = ((ObjectNode) element).deepCopy();
            parameters.remove("type");
            parameters.remove(STEP_ID);
        } else {
            type = LEGACY_EVENT;
            ObjectNode event = ((ObjectNode) element).deepCopy();
            event.remove(STEP_ID);
            event.remove(PERMANENT);
            parameters = JsonNodeFactory.instance.objectNode();
            parameters.set("event", event);
        }
        ActionHandler handler = context.actions().handler(type);
        if (handler == null) {
            report.error(DiagnosticCode.UNKNOWN_ACTION, path, type == LEGACY_EVENT
                            ? "v1 event '" + rawType + "' cannot run here: the v1 bridge is not available"
                            : "action type " + type + " is not registered",
                    "check the type name, and that the mod providing it is installed");
            return null;
        }
        boolean permanent = element.has(PERMANENT)
                ? element.get(PERMANENT).asBoolean(false)
                : handler.external(parameters);
        parameters.remove(PERMANENT);
        if (!parameters.has("package")) {
            parameters.put("package", context.packageId());
        }
        handler.validate(parameters, path, context, report);
        return new ActionDefinition(type, parameters, key, permanent, path);
    }
}
