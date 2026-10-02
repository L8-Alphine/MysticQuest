package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.All;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Any;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtLeast;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtMost;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Constant;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Custom;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Exactly;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.None;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Not;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.TagExists;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.VariableCompare;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.VariableExists;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Xor;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.state.TagSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.CompareOperator;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles authored condition JSON into {@link Condition} trees, reporting every problem it finds.
 *
 * <p>Two spellings are accepted. The typed form matches the rest of MysticQuests content:
 * <pre>
 *   { "type": "all", "conditions": [ ... ] }
 *   { "type": "at_least", "count": 2, "conditions": [ ... ] }
 *   { "type": "tag", "tag": "hyzion:druid_temple.entered" }
 *   { "type": "variable", "variable": "hyzion:druid_temple.keys_found", "op": "&gt;=", "value": 4 }
 *   { "type": "mymod:has_skill", "skill": "mining" }
 * </pre>
 * The keyed form reads closer to the specification's examples:
 * <pre>
 *   { "all": [ { "type": "tag", ... }, { "not": { "type": "tag", ... } } ] }
 * </pre>
 *
 * <p>Besides unknown ids, types and scopes, the compiler reports trees that can never pass (an
 * {@code at_least} asking for more children than it has, a variable bound to two different values in
 * one {@code all}) and trees that always pass. An invalid subtree compiles to {@code false}, so a
 * mistake fails closed if a caller chooses to run a tree that had errors.
 */
public final class ConditionCompiler {
    /** The bridge type that evaluates a v1 condition; an un-namespaced type compiles to it. */
    public static final NamespacedId LEGACY_CONDITION = NamespacedId.of("mysticquests", "legacy.condition");

    private static final Set<String> KEYED = Set.of("all", "any", "none", "not", "xor");

    private ConditionCompiler() {
    }

    public static Condition compile(@Nullable JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        if (node == null || !node.isObject()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "a condition must be a JSON object");
            return new Constant(false);
        }
        ObjectNode object = (ObjectNode) node;
        String type = object.path("type").asText("");
        JsonNode keyedChildren = null;
        if (type.isBlank()) {
            for (String key : KEYED) {
                if (object.has(key)) {
                    type = key;
                    keyedChildren = object.get(key);
                    break;
                }
            }
        }
        if (type.isBlank()) {
            report.error(DiagnosticCode.UNKNOWN_CONDITION, path, "condition has no type",
                    "set \"type\", or use one of the keys " + KEYED);
            return new Constant(false);
        }
        return switch (type.trim().toLowerCase(Locale.ROOT).replace('-', '_')) {
            case "all", "and" -> all(children(object, keyedChildren, path, context, report), path, report);
            case "any", "or" -> any(children(object, keyedChildren, path, context, report), path, report);
            case "none", "nor" -> none(children(object, keyedChildren, path, context, report), path, report);
            case "not" -> not(object, keyedChildren, path, context, report);
            case "xor" -> xor(children(object, keyedChildren, path, context, report), path, report);
            case "at_least", "atleast" -> counted(Counted.AT_LEAST, object, path, context, report);
            case "at_most", "atmost" -> counted(Counted.AT_MOST, object, path, context, report);
            case "exactly" -> counted(Counted.EXACTLY, object, path, context, report);
            case "tag" -> tag(object, path, context, report);
            case "variable" -> variable(object, path, context, report);
            default -> custom(type.trim(), object, path, context, report);
        };
    }

    // --- Combinators ---

    private static List<Condition> children(ObjectNode object, @Nullable JsonNode keyed, String path,
                                            CompileContext context, DiagnosticReport report) {
        JsonNode array = keyed != null ? keyed : object.get("conditions");
        if (array == null || !array.isArray()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "expected an array of conditions");
            return List.of();
        }
        List<Condition> children = new ArrayList<>(array.size());
        for (int index = 0; index < array.size(); index++) {
            children.add(compile(array.get(index), path + "[" + index + "]", context, report));
        }
        return children;
    }

    private static Condition all(List<Condition> children, String path, DiagnosticReport report) {
        if (children.isEmpty()) {
            report.warning(DiagnosticCode.REDUNDANT_CONDITION, path, "an empty 'all' is always true");
        }
        checkContradictions(children, path, report);
        return new All(children);
    }

    private static Condition any(List<Condition> children, String path, DiagnosticReport report) {
        if (children.isEmpty()) {
            report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path, "an empty 'any' can never pass");
        }
        for (Condition child : children) {
            if (children.contains(new Not(child))) {
                report.warning(DiagnosticCode.REDUNDANT_CONDITION, path,
                        "'any' contains a condition and its negation, so it is always true");
                break;
            }
        }
        return new Any(children);
    }

    private static Condition none(List<Condition> children, String path, DiagnosticReport report) {
        if (children.isEmpty()) {
            report.warning(DiagnosticCode.REDUNDANT_CONDITION, path, "an empty 'none' is always true");
        }
        return new None(children);
    }

    private static Condition not(ObjectNode object, @Nullable JsonNode keyed, String path,
                                 CompileContext context, DiagnosticReport report) {
        JsonNode child = keyed != null ? keyed : object.get("condition");
        if (child == null && object.has("conditions")) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path,
                    "'not' takes a single \"condition\"", "wrap several in 'none' instead");
            return new Constant(false);
        }
        return new Not(compile(child, path + ".condition", context, report));
    }

    private static Condition xor(List<Condition> children, String path, DiagnosticReport report) {
        if (children.size() != 2) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path,
                    "'xor' takes exactly two conditions, got " + children.size(),
                    "use {\"type\": \"exactly\", \"count\": 1, ...} for more than two");
            return new Constant(false);
        }
        if (children.get(1).equals(new Not(children.get(0))) || children.get(0).equals(new Not(children.get(1)))) {
            report.warning(DiagnosticCode.REDUNDANT_CONDITION, path,
                    "'xor' of a condition and its negation is always true");
        }
        return new Xor(children.get(0), children.get(1));
    }

    private enum Counted { AT_LEAST, AT_MOST, EXACTLY }

    private static Condition counted(Counted kind, ObjectNode object, String path,
                                     CompileContext context, DiagnosticReport report) {
        List<Condition> children = children(object, null, path, context, report);
        JsonNode countNode = object.get("count");
        if (countNode == null || !countNode.canConvertToInt() || !countNode.isIntegralNumber()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "needs an integer \"count\"");
            return new Constant(false);
        }
        int count = countNode.intValue();
        int size = children.size();
        switch (kind) {
            case AT_LEAST -> {
                if (count > size) {
                    report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path,
                            "at_least " + count + " of " + size + " conditions can never pass");
                } else if (count <= 0) {
                    report.warning(DiagnosticCode.REDUNDANT_CONDITION, path, "at_least " + count + " is always true");
                }
                return new AtLeast(count, children);
            }
            case AT_MOST -> {
                if (count < 0) {
                    report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path, "at_most " + count + " can never pass");
                } else if (count >= size) {
                    report.warning(DiagnosticCode.REDUNDANT_CONDITION, path,
                            "at_most " + count + " of " + size + " conditions is always true");
                }
                return new AtMost(count, children);
            }
            default -> {
                if (count < 0 || count > size) {
                    report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path,
                            "exactly " + count + " of " + size + " conditions can never pass");
                }
                return new Exactly(count, children);
            }
        }
    }

    // --- Leaves ---

    private static Condition tag(ObjectNode object, String path, CompileContext context, DiagnosticReport report) {
        NamespacedId id = id(object, "tag", path, report);
        if (id == null) {
            return new Constant(false);
        }
        TagSchema schema = context.schemas().tag(id);
        if (schema == null) {
            report.error(DiagnosticCode.UNKNOWN_TAG, path, "tag " + id + " is not declared",
                    "declare it under tagSchemas");
            return new Constant(false);
        }
        if (!ContentParams.checkScope(object, schema.scope(), path, context, report)) {
            return new Constant(false);
        }
        Condition leaf = new TagExists(ContentParams.scope(object), id);
        return object.path("invert").asBoolean(false) ? new Not(leaf) : leaf;
    }

    private static Condition variable(ObjectNode object, String path, CompileContext context, DiagnosticReport report) {
        NamespacedId id = id(object, object.has("variable") ? "variable" : "key", path, report);
        if (id == null) {
            return new Constant(false);
        }
        VariableSchema schema = context.schemas().variable(id);
        if (schema == null) {
            report.error(DiagnosticCode.UNKNOWN_VARIABLE, path, "variable " + id + " is not declared",
                    "declare it under variableSchemas");
            return new Constant(false);
        }
        if (!ContentParams.checkScope(object, schema.scope(), path, context, report)) {
            return new Constant(false);
        }
        VariableScope scope = ContentParams.scope(object);
        String opText = object.path("op").asText(object.path("operator").asText("=="));
        if (opText.equalsIgnoreCase("exists")) {
            return new VariableExists(scope, id);
        }
        CompareOperator operator = CompareOperator.parse(opText);
        if (operator == null) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "unknown operator '" + opText + "'",
                    "use ==, !=, >, >=, <, <=, contains or exists");
            return new Constant(false);
        }
        TypeSpec type = schema.type();
        if (operator.ordering() && !type.type().ordered()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path,
                    "operator " + operator.symbol() + " needs an ordered type but " + id + " is " + type);
            return new Constant(false);
        }
        TypeSpec operandType = type;
        if (operator == CompareOperator.CONTAINS) {
            operandType = switch (type.type()) {
                case LIST, SET -> type.element();
                case MAP, STRING -> TypeSpec.of(ValueType.STRING);
                default -> null;
            };
            if (operandType == null) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path,
                        "'contains' needs a list, set, map or string but " + id + " is " + type);
                return new Constant(false);
            }
        }
        Coercion operand = ValueCodec.coerce(operandType, object.get("value"));
        if (!operand.accepted()) {
            report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, path, "value for " + id + ": " + operand.problem());
            return new Constant(false);
        }
        return new VariableCompare(scope, id, operator, operand.value());
    }

    /**
     * A namespaced registered type, or an un-namespaced v1 type ({@code questCompleted},
     * {@code permission}, …) run through {@link #LEGACY_CONDITION} when the v1 bridge is installed.
     */
    private static Condition custom(String type, ObjectNode object, String path, CompileContext context, DiagnosticReport report) {
        boolean legacy = type.indexOf(':') < 0;
        NamespacedId id = legacy ? LEGACY_CONDITION : NamespacedId.tryParse(type).orElse(null);
        if (id == null) {
            report.error(DiagnosticCode.INVALID_ID, path, "condition type: " + NamespacedId.describeProblem(type));
            return new Constant(false);
        }
        ConditionHandler handler = context.conditions().handler(id);
        if (handler == null) {
            report.error(DiagnosticCode.UNKNOWN_CONDITION, path, legacy
                            ? "unknown condition type '" + type + "'"
                            : "condition type " + id + " is not registered",
                    legacy
                            ? "use all, any, none, not, xor, at_least, at_most, exactly, tag, variable, or a namespaced registered type"
                            : "check the mod that provides it is installed and enabled");
            return new Constant(false);
        }
        ObjectNode parameters;
        if (legacy) {
            parameters = object.objectNode();
            parameters.set("condition", object.deepCopy());
        } else {
            parameters = object.deepCopy();
            parameters.remove("type");
        }
        if (!parameters.has("package")) {
            parameters.put("package", context.packageId());
        }
        handler.validate(parameters, path, report);
        return new Custom(id, parameters, path);
    }

    @Nullable
    private static NamespacedId id(ObjectNode object, String field, String path, DiagnosticReport report) {
        return ContentParams.id(object, field, path, report);
    }

    // --- Contradictions ---

    /**
     * Finds children of one {@code all} that cannot all hold: a condition beside its own negation, or
     * numeric bounds on one variable with an empty intersection ({@code >= 5} and {@code < 3}).
     */
    private static void checkContradictions(List<Condition> children, String path, DiagnosticReport report) {
        for (Condition child : children) {
            if (children.contains(new Not(child))) {
                report.error(DiagnosticCode.CONTRADICTORY_CONDITION, path,
                        "'all' requires a condition and its negation, so it can never pass");
                return;
            }
        }
        Map<String, Bounds> bounds = new HashMap<>();
        for (Condition child : children) {
            if (child instanceof VariableCompare compare) {
                BigDecimal operand = number(compare.operand());
                if (operand != null) {
                    String key = (compare.scope() == null ? "" : compare.scope().id()) + "/" + compare.variable();
                    bounds.computeIfAbsent(key, ignored -> new Bounds()).apply(compare.operator(), operand);
                }
            }
        }
        for (Map.Entry<String, Bounds> entry : bounds.entrySet()) {
            if (entry.getValue().empty()) {
                report.error(DiagnosticCode.CONTRADICTORY_CONDITION, path,
                        "'all' bounds " + entry.getKey().substring(entry.getKey().indexOf('/') + 1)
                                + " to no possible value");
            }
        }
    }

    @Nullable
    private static BigDecimal number(QuestValue value) {
        return switch (value) {
            case QuestValue.IntValue integer -> BigDecimal.valueOf(integer.value());
            case QuestValue.LongValue longValue -> BigDecimal.valueOf(longValue.value());
            case QuestValue.DoubleValue doubleValue -> BigDecimal.valueOf(doubleValue.value());
            default -> null;
        };
    }

    /** The interval of values a set of comparisons on one variable still allows. */
    private static final class Bounds {
        private BigDecimal lower;
        private boolean lowerInclusive = true;
        private BigDecimal upper;
        private boolean upperInclusive = true;
        private BigDecimal equal;
        private boolean conflictingEquals;
        private final List<BigDecimal> excluded = new ArrayList<>();

        void apply(CompareOperator operator, BigDecimal value) {
            switch (operator) {
                case EQ -> {
                    if (equal != null && equal.compareTo(value) != 0) {
                        conflictingEquals = true;
                    }
                    equal = value;
                }
                case NE -> excluded.add(value);
                case GT -> raiseLower(value, false);
                case GTE -> raiseLower(value, true);
                case LT -> lowerUpper(value, false);
                case LTE -> lowerUpper(value, true);
                default -> {
                }
            }
        }

        private void raiseLower(BigDecimal value, boolean inclusive) {
            int compared = lower == null ? 1 : value.compareTo(lower);
            if (compared > 0 || compared == 0 && !inclusive) {
                lower = value;
                lowerInclusive = inclusive;
            }
        }

        private void lowerUpper(BigDecimal value, boolean inclusive) {
            int compared = upper == null ? -1 : value.compareTo(upper);
            if (compared < 0 || compared == 0 && !inclusive) {
                upper = value;
                upperInclusive = inclusive;
            }
        }

        boolean empty() {
            if (conflictingEquals) {
                return true;
            }
            if (lower != null && upper != null) {
                int compared = lower.compareTo(upper);
                if (compared > 0 || compared == 0 && !(lowerInclusive && upperInclusive)) {
                    return true;
                }
            }
            if (equal != null) {
                if (lower != null) {
                    int compared = equal.compareTo(lower);
                    if (compared < 0 || compared == 0 && !lowerInclusive) {
                        return true;
                    }
                }
                if (upper != null) {
                    int compared = equal.compareTo(upper);
                    if (compared > 0 || compared == 0 && !upperInclusive) {
                        return true;
                    }
                }
                for (BigDecimal value : excluded) {
                    if (value.compareTo(equal) == 0) {
                        return true;
                    }
                }
            }
            return false;
        }
    }
}
