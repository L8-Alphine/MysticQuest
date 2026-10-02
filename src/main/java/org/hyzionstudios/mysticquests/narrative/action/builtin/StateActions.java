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
import org.hyzionstudios.mysticquests.narrative.state.QuestTagService;
import org.hyzionstudios.mysticquests.narrative.state.QuestVariableService;
import org.hyzionstudios.mysticquests.narrative.state.TagSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.IntNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.time.Duration;

/**
 * The built-in tag and variable actions.
 *
 * <pre>
 *   { "type": "mysticquests:tag.add",    "tag": "hyzion:avalon.discovered", "ttl": "10m" }
 *   { "type": "mysticquests:tag.remove", "tag": "hyzion:avalon.discovered" }
 *   { "type": "mysticquests:tag.toggle", "tag": "hyzion:lever.pulled", "scope": "quest_session" }
 *   { "type": "mysticquests:variable.set",       "variable": "hyzion:druid_temple.phase", "value": "awake" }
 *   { "type": "mysticquests:variable.increment", "variable": "hyzion:druid_temple.keys_found", "amount": 1 }
 *   { "type": "mysticquests:variable.remove",    "variable": "hyzion:druid_temple.phase" }
 * </pre>
 *
 * <p>Every one validates at load against the schemas being loaded: undeclared ids, wrong scopes and
 * values of the wrong type are reload errors, not runtime surprises.
 */
public final class StateActions {
    public static final NamespacedId TAG_ADD = NamespacedId.of("mysticquests", "tag.add");
    public static final NamespacedId TAG_REMOVE = NamespacedId.of("mysticquests", "tag.remove");
    public static final NamespacedId TAG_TOGGLE = NamespacedId.of("mysticquests", "tag.toggle");
    public static final NamespacedId VARIABLE_SET = NamespacedId.of("mysticquests", "variable.set");
    public static final NamespacedId VARIABLE_INCREMENT = NamespacedId.of("mysticquests", "variable.increment");
    public static final NamespacedId VARIABLE_REMOVE = NamespacedId.of("mysticquests", "variable.remove");

    private StateActions() {
    }

    public static void register(ActionTypeRegistry registry, QuestTagService tags, QuestVariableService variables) {
        registry.registerBuiltIn(TAG_ADD, new TagAction(Op.ADD, tags));
        registry.registerBuiltIn(TAG_REMOVE, new TagAction(Op.REMOVE, tags));
        registry.registerBuiltIn(TAG_TOGGLE, new TagAction(Op.TOGGLE, tags));
        registry.registerBuiltIn(VARIABLE_SET, new VariableAction(Op.SET, variables));
        registry.registerBuiltIn(VARIABLE_INCREMENT, new VariableAction(Op.INCREMENT, variables));
        registry.registerBuiltIn(VARIABLE_REMOVE, new VariableAction(Op.REMOVE, variables));
    }

    private enum Op { ADD, REMOVE, TOGGLE, SET, INCREMENT }

    private record TagAction(Op op, QuestTagService tags) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            NamespacedId tag = ContentParams.id(parameters, "tag");
            return ActionResult.of(switch (op) {
                case ADD -> tags.add(context.scope(), ContentParams.scope(parameters), tag, ttl(parameters), context.source());
                case REMOVE -> tags.remove(context.scope(), ContentParams.scope(parameters), tag);
                default -> tags.toggle(context.scope(), ContentParams.scope(parameters), tag, context.source());
            });
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            NamespacedId tag = ContentParams.id(parameters, "tag", path, report);
            if (tag == null) {
                return;
            }
            TagSchema schema = context.schemas().tag(tag);
            if (schema == null) {
                report.error(DiagnosticCode.UNKNOWN_TAG, path, "tag " + tag + " is not declared", "declare it under tagSchemas");
                return;
            }
            ContentParams.checkScope(parameters, schema.scope(), path, context, report);
            if (parameters.hasNonNull("ttl")) {
                Duration ttl = ttl(parameters);
                if (op != Op.ADD) {
                    report.warning(DiagnosticCode.INVALID_PARAMETER, path, "\"ttl\" only applies to tag.add");
                } else if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, path,
                            "\"ttl\" must be a positive duration such as '30s', '10m' or 'PT1H'");
                }
            }
        }

        @Nullable
        private static Duration ttl(JsonNode parameters) {
            JsonNode node = parameters.get("ttl");
            if (node == null || node.isNull()) {
                return null;
            }
            return node.isIntegralNumber() ? Duration.ofMillis(node.longValue()) : ValueCodec.parseDuration(node.asText());
        }
    }

    private record VariableAction(Op op, QuestVariableService variables) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            NamespacedId variable = ContentParams.id(parameters, "variable");
            return ActionResult.of(switch (op) {
                case SET -> variables.setRaw(context.scope(), ContentParams.scope(parameters), variable, parameters.get("value"));
                case INCREMENT -> variables.increment(context.scope(), ContentParams.scope(parameters), variable, amount(parameters));
                default -> variables.remove(context.scope(), ContentParams.scope(parameters), variable);
            });
        }

        private static QuestValue amount(JsonNode parameters) {
            JsonNode amount = parameters.has("amount") ? parameters.get("amount") : IntNode.valueOf(1);
            if (amount.isIntegralNumber()) {
                return new QuestValue.LongValue(amount.longValue());
            }
            if (amount.isNumber()) {
                return new QuestValue.DoubleValue(amount.doubleValue());
            }
            Duration duration = ValueCodec.parseDuration(amount.asText());
            return duration != null ? new QuestValue.DurationValue(duration) : new QuestValue.StringValue(amount.asText());
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            NamespacedId variable = ContentParams.id(parameters, "variable", path, report);
            if (variable == null) {
                return;
            }
            VariableSchema schema = context.schemas().variable(variable);
            if (schema == null) {
                report.error(DiagnosticCode.UNKNOWN_VARIABLE, path, "variable " + variable + " is not declared",
                        "declare it under variableSchemas");
                return;
            }
            ContentParams.checkScope(parameters, schema.scope(), path, context, report);
            switch (op) {
                case SET -> {
                    Coercion value = ValueCodec.coerce(schema.type(), parameters.get("value"));
                    if (!value.accepted()) {
                        report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, path, "value for " + variable + ": " + value.problem());
                    }
                }
                case INCREMENT -> {
                    ValueType type = schema.type().type();
                    if (!type.numeric() && type != ValueType.DURATION) {
                        report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, path,
                                variable + " is " + schema.type() + " and cannot be incremented");
                        return;
                    }
                    Coercion amount = ValueCodec.coerceText(schema.type(), ValueCodec.toText(amount(parameters)));
                    if (!amount.accepted()) {
                        report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, path, "amount for " + variable + ": " + amount.problem());
                    }
                }
                default -> {
                }
            }
        }
    }
}
