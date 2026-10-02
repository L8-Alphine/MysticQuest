package org.hyzionstudios.mysticquests.narrative.puzzle;

import org.hyzionstudios.mysticquests.narrative.session.SessionComponent;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One audience's progress on one puzzle, stored as a component of their session.
 *
 * <p>The selected inputs are stored directly, as the specification asks, not re-derived from the seed
 * on load. A content change to the candidate list therefore cannot silently move a player's keys.
 * The seed is kept as well, so staff can see how a selection came about.
 *
 * <p>Not thread-safe on its own; the puzzle service mutates it only while holding the session's
 * monitor.
 */
public final class PuzzleState implements SessionComponent {
    private int generation;
    private long seed;
    @Nullable
    private List<String> selected;
    private final Map<String, Instant> activated = new LinkedHashMap<>();
    private final List<String> sequenceProgress = new ArrayList<>();
    @Nullable
    private String machineState;
    private int accepted;
    private int mistakes;
    private boolean completed;
    @Nullable
    private Instant completedAt;

    /** Bumped on every reset; part of every ledger key, so each round fires its own outputs once. */
    public int generation() {
        return generation;
    }

    public long seed() {
        return seed;
    }

    /** The inputs that count for this audience; null until selection has run. */
    @Nullable
    public List<String> selected() {
        return selected == null ? null : Collections.unmodifiableList(selected);
    }

    void select(long seed, List<String> selected) {
        this.seed = seed;
        this.selected = new ArrayList<>(selected);
    }

    /** Activated inputs and when, in activation order. */
    public Map<String, Instant> activated() {
        return Collections.unmodifiableMap(activated);
    }

    void activate(String input, Instant at) {
        activated.put(input, at);
    }

    boolean deactivate(String input) {
        return activated.remove(input) != null;
    }

    /** Drops activations at or before {@code cutoff}; for TIMED windows. */
    void expireBefore(Instant cutoff) {
        activated.values().removeIf(at -> !at.isAfter(cutoff));
    }

    public List<String> sequenceProgress() {
        return Collections.unmodifiableList(sequenceProgress);
    }

    void advanceSequence(String input) {
        sequenceProgress.add(input);
    }

    void clearProgress() {
        activated.clear();
        sequenceProgress.clear();
    }

    @Nullable
    public String machineState() {
        return machineState;
    }

    void setMachineState(@Nullable String machineState) {
        this.machineState = machineState;
    }

    /** Accepted activations this round; numbers each input's ledger key so toggles re-fire. */
    public int accepted() {
        return accepted;
    }

    int nextAccepted() {
        return ++accepted;
    }

    public int mistakes() {
        return mistakes;
    }

    int nextMistake() {
        return ++mistakes;
    }

    public boolean completed() {
        return completed;
    }

    @Nullable
    public Instant completedAt() {
        return completedAt;
    }

    void complete(Instant at) {
        completed = true;
        completedAt = at;
    }

    /** Starts a new round: everything but the selection is cleared, and the generation advances. */
    void newRound(@Nullable String initialMachineState) {
        generation++;
        clearProgress();
        machineState = initialMachineState;
        accepted = 0;
        mistakes = 0;
        completed = false;
        completedAt = null;
    }

    @Override
    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("generation", generation);
        node.put("seed", seed);
        if (selected != null) {
            ArrayNode array = node.putArray("selected");
            selected.forEach(array::add);
        }
        ObjectNode activations = node.putObject("activated");
        activated.forEach((input, at) -> activations.put(input, at.toString()));
        ArrayNode progress = node.putArray("sequenceProgress");
        sequenceProgress.forEach(progress::add);
        if (machineState != null) {
            node.put("machineState", machineState);
        }
        node.put("accepted", accepted);
        node.put("mistakes", mistakes);
        node.put("completed", completed);
        if (completedAt != null) {
            node.put("completedAt", completedAt.toString());
        }
        return node;
    }

    public static PuzzleState fromJson(ObjectNode node) {
        PuzzleState state = new PuzzleState();
        state.generation = node.path("generation").asInt(0);
        state.seed = node.path("seed").asLong(0L);
        if (node.path("selected").isArray()) {
            List<String> selected = new ArrayList<>();
            node.get("selected").forEach(input -> selected.add(input.asText()));
            state.selected = selected;
        }
        node.path("activated").properties().forEach(entry ->
                state.activated.put(entry.getKey(), Instant.parse(entry.getValue().asText())));
        for (JsonNode input : node.path("sequenceProgress")) {
            state.sequenceProgress.add(input.asText());
        }
        state.machineState = node.hasNonNull("machineState") ? node.get("machineState").asText() : null;
        state.accepted = node.path("accepted").asInt(0);
        state.mistakes = node.path("mistakes").asInt(0);
        state.completed = node.path("completed").asBoolean(false);
        state.completedAt = node.hasNonNull("completedAt") ? Instant.parse(node.get("completedAt").asText()) : null;
        return state;
    }
}
