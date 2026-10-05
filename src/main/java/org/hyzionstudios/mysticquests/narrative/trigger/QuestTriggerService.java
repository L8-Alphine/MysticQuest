package org.hyzionstudios.mysticquests.narrative.trigger;

import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;
import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics.Counter;
import org.hyzionstudios.mysticquests.narrative.NarrativeContent.PuzzleBinding;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.InputResult;

import javax.annotation.Nullable;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Routes normalised trigger events (§5.1): logical activation first, then the puzzle inputs bound to
 * the volume.
 *
 * <p>A volume that is logically disabled for the triggering player produces nothing for them, so
 * the per-session disable in a puzzle's outputs silences the remaining key volumes for that
 * audience alone.
 *
 * <p>Bindings are indexed by volume, so an event costs only the inputs bound to its own volume,
 * however many puzzles and players there are (§28). Each event is timed as {@code trigger.event}.
 */
public final class QuestTriggerService {
    /** What one event did: whether it was let through, and the puzzle inputs it fed. */
    public record Routed(boolean enabled, List<InputResult> puzzleInputs) {
    }

    private final Supplier<NarrativeContent> content;
    private final TriggerActivationService activation;
    private final QuestPuzzleService puzzles;
    private final NarrativeMetrics metrics;
    private final Consumer<String> problems;

    public QuestTriggerService(Supplier<NarrativeContent> content, TriggerActivationService activation, QuestPuzzleService puzzles,
                               NarrativeMetrics metrics, Consumer<String> problems) {
        this.content = content;
        this.activation = activation;
        this.puzzles = puzzles;
        this.metrics = metrics;
        this.problems = problems;
    }

    public TriggerActivationService activation() {
        return activation;
    }

    /** @param world the world the volume is in, passed to the actions the inputs run */
    public Routed handle(TriggerEvent event, @Nullable String world) {
        metrics.increment(Counter.TRIGGER_EVENTS);
        long started = System.nanoTime();
        Routed routed = route(event, world);
        long elapsed = System.nanoTime() - started;
        metrics.add(Counter.PUZZLE_INPUTS, routed.puzzleInputs().size());
        if (metrics.time("trigger.event", elapsed)) {
            problems.accept("slow trigger event " + event.eventType() + " on " + event.volumeKey() + " took "
                    + Duration.ofNanos(elapsed).toMillis() + "ms (" + routed.puzzleInputs().size() + " puzzle inputs)");
        }
        return routed;
    }

    private Routed route(TriggerEvent event, @Nullable String world) {
        if (event.player() == null) {
            return new Routed(activation.isEnabled(event.volumeKey(), null), List.of());
        }
        if (!activation.isEnabled(event.volumeKey(), event.player())) {
            return new Routed(false, List.of());
        }
        List<InputResult> results = new ArrayList<>();
        for (PuzzleBinding binding : content.get().bindings(event.volumeKey())) {
            if (binding.event().equals(event.eventType())) {
                results.add(puzzles.input(event.player(), world, binding.puzzle(), binding.input(), true));
            } else if (binding.toggleable() && "EXIT".equals(event.eventType()) && "ENTER".equals(binding.event())) {
                results.add(puzzles.input(event.player(), world, binding.puzzle(), binding.input(), false));
            }
        }
        return new Routed(true, results);
    }
}
