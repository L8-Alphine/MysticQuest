package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.condition.Condition;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionCompiler;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.AudienceMode;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Input;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.MachineState;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Rule;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Selection;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.StateMachine;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.MissingNode;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

/**
 * Compiles authored puzzle JSON into a {@link PuzzleDefinition}, rejecting puzzles that are
 * malformed or can never be solved.
 *
 * <pre>
 * {
 *   "id": "hyzion:druid_temple.hidden_keys",
 *   "story": "avalon:druid_temple",
 *   "audience": "auto",
 *   "inputs": [ { "id": "key_1", "volume": "avalon:druid_key_1" }, ... ],
 *   "selection": { "active": 4 },
 *   "rule": "all",
 *   "outputs": [ { "type": "mysticquests:tag.add", "tag": "hyzion:druid_temple.keys_complete" } ]
 * }
 * </pre>
 *
 * <p>Solvability is checked for every rule. An {@code n_of_m} cannot ask for more inputs than are
 * active, a weighted puzzle cannot need more weight than its inputs carry, and a state machine must
 * be able to reach a terminal state. A puzzle that cannot be finished is a reload error, rather than
 * a player stuck in a temple forever.
 */
public final class PuzzleCompiler {
    private PuzzleCompiler() {
    }

    @Nullable
    public static PuzzleDefinition compile(JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        if (node == null || !node.isObject()) {
            report.error(DiagnosticCode.INVALID_PUZZLE, path, "a puzzle must be a JSON object");
            return null;
        }
        NamespacedId id = ContentParams.id(node, "id", path, report);
        if (id == null) {
            return null;
        }
        String puzzlePath = path + "<" + id + ">";
        int errorsBefore = report.errors().size();

        String story = node.path("story").asText(id.toString()).trim();
        if (story.isEmpty()) {
            report.error(DiagnosticCode.INVALID_PUZZLE, puzzlePath, "\"story\" must not be blank");
        }
        AudienceMode audience = audience(node, puzzlePath, context, report);
        List<Input> inputs = inputs(node.get("inputs"), puzzlePath, report);
        Set<String> inputIds = new LinkedHashSet<>();
        inputs.forEach(input -> inputIds.add(input.id()));

        Rule rule = rule(node.get("rule"), inputIds, puzzlePath, context, report);
        Selection selection = selection(node.get("selection"), inputIds, rule, puzzlePath, report);
        if (rule != null) {
            rule = withDefaults(rule, selection != null ? selection.active() : inputs.size());
            checkSolvable(rule, inputs, selection, puzzlePath, report);
        }

        Condition requires = node.hasNonNull("requires")
                ? ConditionCompiler.compile(node.get("requires"), puzzlePath + ".requires", context, report)
                : null;
        List<ActionDefinition> outputs = outputs(node.get("outputs"), puzzlePath + ".outputs", context, report);
        List<ActionDefinition> onInput = ActionCompiler.compile(node.get("onInput"), puzzlePath + ".onInput", context, report);
        List<ActionDefinition> onMistake = ActionCompiler.compile(node.get("onMistake"), puzzlePath + ".onMistake", context, report);
        List<ActionDefinition> onReset = ActionCompiler.compile(node.get("onReset"), puzzlePath + ".onReset", context, report);
        if (node.path("outputs").isEmpty()) {
            report.warning(DiagnosticCode.INVALID_PUZZLE_OUTPUT, puzzlePath, "has no outputs, so solving it changes nothing");
        }
        if (!onMistake.isEmpty() && rule != null && rule.type() != PuzzleRuleType.SEQUENCE) {
            report.warning(DiagnosticCode.INVALID_PARAMETER, puzzlePath, "\"onMistake\" only fires for sequence puzzles");
        }

        if (report.errors().size() > errorsBefore || rule == null) {
            return null;
        }
        return new PuzzleDefinition(id, context.packageId(), story, audience, inputs, selection, rule, requires,
                node.path("repeatable").asBoolean(false), outputs, onInput, onMistake, onReset);
    }

    private static AudienceMode audience(JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        String raw = node.path("audience").asText("auto").trim().toUpperCase(Locale.ROOT);
        AudienceMode mode;
        try {
            mode = AudienceMode.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "unknown audience '" + raw.toLowerCase(Locale.ROOT) + "'",
                    "use player, party or auto");
            return AudienceMode.AUTO;
        }
        if (mode == AudienceMode.PARTY && context.scopes().status(VariableScope.PARTY).level() != ScopeSupport.Level.SUPPORTED) {
            report.warning(DiagnosticCode.MISSING_INTEGRATION, path,
                    "party audience, but " + context.scopes().status(VariableScope.PARTY).reason());
        }
        return mode;
    }

    private static List<Input> inputs(@Nullable JsonNode node, String path, DiagnosticReport report) {
        List<Input> inputs = new ArrayList<>();
        if (node == null || !node.isArray() || node.isEmpty()) {
            report.error(DiagnosticCode.INVALID_PUZZLE, path, "needs a non-empty \"inputs\" array");
            return inputs;
        }
        Set<String> seen = new HashSet<>();
        for (int index = 0; index < node.size(); index++) {
            JsonNode entry = node.get(index);
            String inputPath = path + ".inputs[" + index + "]";
            String id = entry.isTextual() ? entry.asText().trim() : entry.path("id").asText("").trim();
            if (id.isEmpty() || id.indexOf(':') >= 0) {
                report.error(DiagnosticCode.INVALID_ID, inputPath, "input id must be non-blank and contain no ':'");
                continue;
            }
            if (!seen.add(id)) {
                report.error(DiagnosticCode.DUPLICATE_ID, inputPath, "input '" + id + "' is declared twice");
                continue;
            }
            int weight = entry.path("weight").asInt(1);
            if (weight <= 0) {
                report.error(DiagnosticCode.INVALID_PARAMETER, inputPath, "weight must be positive");
            }
            String volume = entry.path("volume").asText("").trim();
            String event = entry.path("event").asText("ENTER").trim().toUpperCase(Locale.ROOT);
            String group = entry.hasNonNull("group") ? entry.get("group").asText().trim() : null;
            inputs.add(new Input(id, weight, group == null || group.isEmpty() ? null : group,
                    volume.isEmpty() ? null : volume, event, entry.path("toggleable").asBoolean(false)));
        }
        return inputs;
    }

    @Nullable
    private static Selection selection(@Nullable JsonNode node, Set<String> inputIds, @Nullable Rule rule,
                                       String path, DiagnosticReport report) {
        if (node == null || node.isNull()) {
            return null;
        }
        String selectionPath = path + ".selection";
        List<String> candidates = new ArrayList<>();
        if (node.has("candidates")) {
            Set<String> seen = new HashSet<>();
            for (JsonNode candidate : node.get("candidates")) {
                String id = candidate.asText();
                if (!inputIds.contains(id)) {
                    report.error(DiagnosticCode.MISSING_REFERENCE, selectionPath, "candidate '" + id + "' is not an input");
                } else if (seen.add(id)) {
                    candidates.add(id);
                }
            }
        } else {
            candidates.addAll(inputIds);
        }
        int active = node.path("active").asInt(-1);
        if (active < 1 || active > candidates.size()) {
            report.error(DiagnosticCode.INVALID_PUZZLE, selectionPath,
                    "\"active\" must be between 1 and the " + candidates.size() + " candidates, got " + node.path("active").asText());
            return null;
        }
        if (rule != null && (rule.type() == PuzzleRuleType.SEQUENCE
                || rule.type() == PuzzleRuleType.UNORDERED_SEQUENCE
                || rule.type() == PuzzleRuleType.STATE_MACHINE)) {
            report.error(DiagnosticCode.INVALID_PUZZLE, selectionPath,
                    "random selection cannot be combined with a " + rule.type().name().toLowerCase(Locale.ROOT)
                            + " rule, which names its inputs explicitly");
            return null;
        }
        return new Selection(candidates, active);
    }

    /** A timed rule with no {@code required} needs every active input inside the window. */
    private static Rule withDefaults(Rule rule, int active) {
        if (rule.type() != PuzzleRuleType.TIMED || rule.required() >= 0) {
            return rule;
        }
        return new Rule(rule.type(), active, rule.sequence(), rule.window(), rule.threshold(), rule.perGroup(),
                rule.resetOnMistake(), rule.machine());
    }

    @Nullable
    private static Rule rule(@Nullable JsonNode node, Set<String> inputIds, String path,
                             CompileContext context, DiagnosticReport report) {
        String rulePath = path + ".rule";
        JsonNode body = node != null && node.isObject() ? node : null;
        String typeText = node == null ? "" : node.isTextual() ? node.asText() : node.path("type").asText("");
        PuzzleRuleType type = PuzzleRuleType.parse(typeText);
        if (type == null) {
            report.error(DiagnosticCode.INVALID_PUZZLE, rulePath, "unknown or missing rule type '" + typeText + "'",
                    "use all, any, n_of_m, sequence, unordered_sequence, exact, timed, weighted, groups or state_machine");
            return null;
        }
        JsonNode params = body == null ? MissingNode.getInstance() : body;
        List<String> sequence = new ArrayList<>();
        for (JsonNode step : params.path("sequence")) {
            sequence.add(step.asText());
        }
        Duration window = null;
        if (params.hasNonNull("window")) {
            JsonNode raw = params.get("window");
            window = raw.isIntegralNumber() ? Duration.ofMillis(raw.longValue()) : ValueCodec.parseDuration(raw.asText());
        }
        StateMachine machine = type == PuzzleRuleType.STATE_MACHINE ? machine(params, inputIds, rulePath, context, report) : null;
        return new Rule(
                type,
                params.path("required").asInt(-1),
                sequence,
                window,
                params.path("threshold").asInt(0),
                params.path("perGroup").asInt(1),
                params.path("resetOnMistake").asBoolean(true),
                machine);
    }

    @Nullable
    private static StateMachine machine(JsonNode params, Set<String> inputIds, String path,
                                        CompileContext context, DiagnosticReport report) {
        JsonNode states = params.get("states");
        if (states == null || !states.isObject() || states.isEmpty()) {
            report.error(DiagnosticCode.INVALID_PUZZLE, path, "state_machine needs a \"states\" object");
            return null;
        }
        Map<String, MachineState> compiled = new LinkedHashMap<>();
        states.properties().forEach(entry -> {
            String statePath = path + ".states." + entry.getKey();
            Map<String, String> transitions = new LinkedHashMap<>();
            entry.getValue().path("transitions").properties().forEach(transition -> {
                if (!inputIds.contains(transition.getKey())) {
                    report.error(DiagnosticCode.MISSING_REFERENCE, statePath, "transition on unknown input '" + transition.getKey() + "'");
                }
                transitions.put(transition.getKey(), transition.getValue().asText());
            });
            List<ActionDefinition> onEnter = ActionCompiler.compile(entry.getValue().get("onEnter"), statePath + ".onEnter", context, report);
            compiled.put(entry.getKey(), new MachineState(entry.getValue().path("terminal").asBoolean(false), transitions, onEnter));
        });
        String initial = params.path("initial").asText("");
        if (!compiled.containsKey(initial)) {
            report.error(DiagnosticCode.MISSING_REFERENCE, path, "\"initial\" state '" + initial + "' is not defined");
            return null;
        }
        compiled.forEach((name, state) -> state.transitions().forEach((input, target) -> {
            if (!compiled.containsKey(target)) {
                report.error(DiagnosticCode.MISSING_REFERENCE, path + ".states." + name, "transition to unknown state '" + target + "'");
            }
        }));
        return new StateMachine(initial, compiled);
    }

    private static List<ActionDefinition> outputs(@Nullable JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        DiagnosticReport local = new DiagnosticReport();
        List<ActionDefinition> outputs = ActionCompiler.compile(node, path, context, local);
        report.addAll(local);
        if (local.hasErrors()) {
            report.error(DiagnosticCode.INVALID_PUZZLE_OUTPUT, path,
                    local.errors().size() + " output(s) are invalid; the puzzle could never pay out correctly");
        }
        return outputs;
    }

    /** Rejects rules that can never complete with the inputs that will be active. */
    private static void checkSolvable(Rule rule, List<Input> inputs, @Nullable Selection selection, String path, DiagnosticReport report) {
        String rulePath = path + ".rule";
        int active = selection != null ? selection.active() : inputs.size();
        Set<String> ids = new HashSet<>();
        inputs.forEach(input -> ids.add(input.id()));
        switch (rule.type()) {
            case N_OF_M, EXACT, TIMED -> {
                int required = rule.required();
                if (required < 1 || required > active) {
                    report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, rulePath,
                            rule.type().name().toLowerCase(Locale.ROOT) + " needs \"required\" between 1 and the "
                                    + active + " active inputs, got " + rule.required());
                }
                if (rule.type() == PuzzleRuleType.TIMED && (rule.window() == null || rule.window().isZero() || rule.window().isNegative())) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, rulePath, "timed needs a positive \"window\" such as '15s'");
                }
                if (rule.type() == PuzzleRuleType.EXACT && inputs.stream().noneMatch(Input::toggleable)) {
                    report.warning(DiagnosticCode.INVALID_PARAMETER, rulePath,
                            "exact with no toggleable inputs behaves like n_of_m; mark plates \"toggleable\": true");
                }
            }
            case SEQUENCE, UNORDERED_SEQUENCE -> {
                if (rule.sequence().isEmpty()) {
                    report.error(DiagnosticCode.INVALID_PUZZLE, rulePath, "needs a non-empty \"sequence\" of input ids");
                }
                Set<String> seen = new HashSet<>();
                for (String step : rule.sequence()) {
                    if (!ids.contains(step)) {
                        report.error(DiagnosticCode.MISSING_REFERENCE, rulePath, "sequence names unknown input '" + step + "'");
                    } else if (!seen.add(step)) {
                        report.error(DiagnosticCode.INVALID_PUZZLE, rulePath,
                                "sequence repeats input '" + step + "'", "use a state_machine for sequences that revisit an input");
                    }
                }
            }
            case WEIGHTED -> {
                if (rule.threshold() <= 0) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, rulePath, "weighted needs a positive \"threshold\"");
                    return;
                }
                List<Integer> weights = new ArrayList<>();
                List<String> pool = selection != null ? selection.candidates() : inputs.stream().map(Input::id).toList();
                for (Input input : inputs) {
                    if (pool.contains(input.id())) {
                        weights.add(input.weight());
                    }
                }
                weights.sort(Integer::compare);
                int heaviest = weights.subList(Math.max(0, weights.size() - active), weights.size()).stream().mapToInt(Integer::intValue).sum();
                int lightest = weights.subList(0, Math.min(active, weights.size())).stream().mapToInt(Integer::intValue).sum();
                if (rule.threshold() > heaviest) {
                    report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, rulePath,
                            "threshold " + rule.threshold() + " is more than the " + heaviest + " the active inputs can reach");
                } else if (rule.threshold() > lightest) {
                    report.warning(DiagnosticCode.IMPOSSIBLE_CONDITION, rulePath,
                            "threshold " + rule.threshold() + " is unreachable for some random selections (lightest total " + lightest + ")");
                }
            }
            case GROUPS -> {
                if (rule.perGroup() < 1) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, rulePath, "\"perGroup\" must be at least 1");
                }
                Map<String, Integer> sizes = new HashMap<>();
                for (Input input : inputs) {
                    if (input.group() == null) {
                        report.error(DiagnosticCode.INVALID_PUZZLE, rulePath, "input '" + input.id() + "' has no \"group\"");
                    } else {
                        sizes.merge(input.group(), 1, Integer::sum);
                    }
                }
                sizes.forEach((group, size) -> {
                    if (size < rule.perGroup()) {
                        report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, rulePath,
                                "group '" + group + "' has " + size + " inputs but needs " + rule.perGroup());
                    }
                });
                if (selection != null) {
                    report.warning(DiagnosticCode.IMPOSSIBLE_CONDITION, rulePath,
                            "random selection may leave a group with too few active inputs; groups with none active are ignored");
                }
            }
            case STATE_MACHINE -> checkMachine(rule.machine(), rulePath, report);
            default -> {
            }
        }
    }

    private static void checkMachine(@Nullable StateMachine machine, String path, DiagnosticReport report) {
        if (machine == null) {
            return;
        }
        if (machine.states().get(machine.initial()).terminal()) {
            report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path, "the initial state is terminal, so the puzzle is solved before it starts");
        }
        Set<String> reachable = new HashSet<>();
        Deque<String> queue = new ArrayDeque<>();
        queue.add(machine.initial());
        while (!queue.isEmpty()) {
            String state = queue.poll();
            if (!reachable.add(state) || !machine.states().containsKey(state)) {
                continue;
            }
            queue.addAll(machine.states().get(state).transitions().values());
        }
        boolean terminalReachable = machine.states().entrySet().stream()
                .anyMatch(entry -> entry.getValue().terminal() && reachable.contains(entry.getKey()));
        if (!terminalReachable) {
            report.error(DiagnosticCode.IMPOSSIBLE_CONDITION, path, "no terminal state is reachable from '" + machine.initial() + "'");
        }
        for (String state : machine.states().keySet()) {
            if (!reachable.contains(state)) {
                report.warning(DiagnosticCode.UNREACHABLE_NODE, path, "state '" + state + "' can never be reached");
            }
        }
    }
}
