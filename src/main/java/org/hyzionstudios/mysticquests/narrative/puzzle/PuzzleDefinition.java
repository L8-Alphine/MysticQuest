package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.condition.Condition;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;

/**
 * A compiled, immutable puzzle: INPUTS → RULE → STATE → OUTPUTS (§10).
 *
 * @param story the session the puzzle's state lives in, usually the quest id. Two puzzles with the
 *         same story share a session and its {@code quest_session} state.
 * @param requires gate on every input, or null; e.g. "only after entering the temple"
 * @param repeatable after completion, start a new round automatically, so the puzzle can be solved
 *         and its outputs fire again; otherwise a completed puzzle ignores input until reset
 */
public record PuzzleDefinition(
        NamespacedId id,
        String packageId,
        String story,
        AudienceMode audience,
        List<Input> inputs,
        @Nullable Selection selection,
        Rule rule,
        @Nullable Condition requires,
        boolean repeatable,
        List<ActionDefinition> outputs,
        List<ActionDefinition> onInput,
        List<ActionDefinition> onMistake,
        List<ActionDefinition> onReset) {

    public PuzzleDefinition {
        Objects.requireNonNull(id, "id");
        inputs = List.copyOf(inputs);
        outputs = List.copyOf(outputs);
        onInput = List.copyOf(onInput);
        onMistake = List.copyOf(onMistake);
        onReset = List.copyOf(onReset);
    }

    /** Whose session the puzzle runs in. */
    public enum AudienceMode {
        PLAYER,
        PARTY,
        /** The party's session when the player is in one, otherwise the player's own. */
        AUTO
    }

    /**
     * One input: something a player does that the puzzle counts.
     *
     * @param group for {@link PuzzleRuleType#GROUPS}; null otherwise
     * @param volume a trigger volume ({@code world:volumeId}) whose event activates this input, or
     *         null when only an effect or action fires it
     * @param event which volume event activates it, normally {@code ENTER}
     * @param toggleable the input can be deactivated again: a pressure plate rather than a key
     *         picked up. A toggleable input bound to a volume deactivates on {@code EXIT}.
     */
    public record Input(String id, int weight, @Nullable String group, @Nullable String volume, String event, boolean toggleable) {
    }

    /**
     * Random selection of the inputs that count for one audience (§10.1).
     *
     * @param candidates the inputs to choose from, in authored order
     * @param active how many to choose; the rest do nothing for that audience
     */
    public record Selection(List<String> candidates, int active) {
        public Selection {
            candidates = List.copyOf(candidates);
        }
    }

    /**
     * Rule parameters. Each rule type reads only the fields it needs.
     *
     * @param required count for N_OF_M, EXACT and TIMED
     * @param sequence order for SEQUENCE, membership for UNORDERED_SEQUENCE
     * @param window TIMED window
     * @param threshold WEIGHTED target
     * @param perGroup GROUPS minimum per group
     * @param resetOnMistake SEQUENCE: whether a wrong input clears progress
     */
    public record Rule(
            PuzzleRuleType type,
            int required,
            List<String> sequence,
            @Nullable Duration window,
            int threshold,
            int perGroup,
            boolean resetOnMistake,
            @Nullable StateMachine machine) {
        public Rule {
            Objects.requireNonNull(type, "type");
            sequence = List.copyOf(sequence);
        }
    }

    /** STATE_MACHINE rule: named states, transitions keyed by input id, terminal states complete. */
    public record StateMachine(String initial, Map<String, MachineState> states) {
        public StateMachine {
            states = Collections.unmodifiableMap(new LinkedHashMap<>(states));
        }
    }

    /** @param onEnter actions run when the machine enters this state */
    public record MachineState(boolean terminal, Map<String, String> transitions, List<ActionDefinition> onEnter) {
        public MachineState {
            transitions = Map.copyOf(transitions);
            onEnter = List.copyOf(onEnter);
        }
    }

    public Optional<Input> input(String inputId) {
        for (Input input : inputs) {
            if (input.id().equals(inputId)) {
                return Optional.of(input);
            }
        }
        return Optional.empty();
    }

    /** The inputs eligible for selection: the selection's candidates, or every input. */
    public List<String> candidates() {
        return selection != null ? selection.candidates() : inputs.stream().map(Input::id).toList();
    }
}
