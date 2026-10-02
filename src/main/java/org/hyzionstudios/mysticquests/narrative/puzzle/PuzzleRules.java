package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Input;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.MachineState;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.Rule;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

/**
 * Decides whether a puzzle's current state satisfies its rule. Pure: no clocks, no services, just the
 * definition and the state. Every rule type is unit-testable on its own.
 */
public final class PuzzleRules {
    private PuzzleRules() {
    }

    /** The inputs that count for this audience: the stored selection, or every input. */
    public static List<String> activeInputs(PuzzleDefinition definition, PuzzleState state) {
        List<String> selected = state.selected();
        return selected != null ? selected : definition.inputs().stream().map(Input::id).toList();
    }

    public static boolean complete(PuzzleDefinition definition, PuzzleState state) {
        Rule rule = definition.rule();
        List<String> active = activeInputs(definition, state);
        Map<String, ?> activated = state.activated();
        return switch (rule.type()) {
            case ALL -> !active.isEmpty() && active.stream().allMatch(activated::containsKey);
            case ANY -> active.stream().anyMatch(activated::containsKey);
            case N_OF_M, TIMED -> countActivated(active, activated) >= rule.required();
            case EXACT -> countActivated(active, activated) == rule.required();
            case SEQUENCE -> state.sequenceProgress().equals(rule.sequence());
            case UNORDERED_SEQUENCE -> rule.sequence().stream().allMatch(activated::containsKey);
            case WEIGHTED -> weight(definition, active, activated) >= rule.threshold();
            case GROUPS -> groupsSatisfied(definition, active, activated, rule.perGroup());
            case STATE_MACHINE -> {
                MachineState current = rule.machine() == null || state.machineState() == null
                        ? null
                        : rule.machine().states().get(state.machineState());
                yield current != null && current.terminal();
            }
        };
    }

    static int countActivated(List<String> active, Map<String, ?> activated) {
        int count = 0;
        for (String input : active) {
            if (activated.containsKey(input)) {
                count++;
            }
        }
        return count;
    }

    private static int weight(PuzzleDefinition definition, List<String> active, Map<String, ?> activated) {
        int total = 0;
        for (String input : active) {
            if (activated.containsKey(input)) {
                total += definition.input(input).map(Input::weight).orElse(0);
            }
        }
        return total;
    }

    /** Every group with at least one active input has at least {@code perGroup} of them activated. */
    private static boolean groupsSatisfied(PuzzleDefinition definition, List<String> active, Map<String, ?> activated, int perGroup) {
        Map<String, Integer> counts = new HashMap<>();
        for (String input : active) {
            String group = definition.input(input).map(Input::group).orElse(null);
            if (group == null) {
                continue;
            }
            counts.merge(group, activated.containsKey(input) ? 1 : 0, Integer::sum);
        }
        return !counts.isEmpty() && counts.values().stream().allMatch(count -> count >= perGroup);
    }
}
