package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;

import java.util.Locale;
import java.util.Objects;

/**
 * Type-agnostic puzzle information supplied by the server.
 *
 * <p>The HUD deliberately receives no puzzle id, seed, candidate ids, solution order, or output
 * actions. It presents only a revealed hint, a semantic status, and session-safe feedback. This
 * works for count, sequence, timed, weighted, grouped, and state-machine puzzles without implying
 * that every puzzle is a row of collectible slots.
 */
public record QuestPuzzleHudState(
        String title,
        String hintText,
        String statusText,
        String sessionLabel,
        Phase phase,
        String feedback) {

    public enum Phase {
        ACTIVE("IN PROGRESS"),
        ACCEPTED("INPUT ACCEPTED"),
        MISTAKE("TRY AGAIN"),
        BLOCKED("LOCKED"),
        COMPLETE("COMPLETE");

        private final String label;

        Phase(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }
    }

    public QuestPuzzleHudState {
        title = clean(title, "PUZZLE");
        hintText = clean(hintText, "Observe the mechanism for clues.");
        statusText = clean(statusText, "Puzzle active");
        sessionLabel = clean(sessionLabel, "Progress is saved");
        phase = Objects.requireNonNullElse(phase, Phase.ACTIVE);
        feedback = clean(feedback, "");
    }

    /**
     * The same card for a party member who did not make the input: progress and phase are shared,
     * but the feedback must not read as if they had acted.
     */
    public QuestPuzzleHudState forPartyMember() {
        String teammate = switch (phase) {
            case ACCEPTED -> "A party member moved the mechanism.";
            case MISTAKE -> "A party member broke the sequence; it resets.";
            case COMPLETE -> "Your party solved it.";
            default -> feedback;
        };
        return new QuestPuzzleHudState(title, hintText, statusText, sessionLabel, phase, teammate);
    }

    /** Redacts a server-side session view into the narrow presentation contract sent to the HUD. */
    public static QuestPuzzleHudState from(
            PuzzleDefinition definition,
            QuestPuzzleService.PuzzleView view,
            QuestPuzzleService.Outcome outcome) {
        int target = target(definition, view);
        int current = current(definition, view, target);
        if (outcome == QuestPuzzleService.Outcome.COMPLETED || view.completed()) {
            current = target;
        }
        Phase phase = switch (outcome) {
            case COMPLETED, IGNORED_COMPLETED -> Phase.COMPLETE;
            case ACCEPTED, DEACTIVATED -> Phase.ACCEPTED;
            case MISTAKE -> Phase.MISTAKE;
            case BLOCKED -> Phase.BLOCKED;
            default -> Phase.ACTIVE;
        };
        return new QuestPuzzleHudState(
                humanize(definition.id().path()),
                hint(definition, target),
                status(definition, current, target, view),
                view.owner().kind() == SessionOwner.Kind.PARTY
                        ? "Shared with your party"
                        : "Personal progress, saved",
                phase,
                feedback(phase));
    }

    private static int target(PuzzleDefinition definition, QuestPuzzleService.PuzzleView view) {
        PuzzleDefinition.Rule rule = definition.rule();
        return Math.max(1, switch (rule.type()) {
            case ANY, STATE_MACHINE -> 1;
            case N_OF_M, EXACT, TIMED -> rule.required();
            case SEQUENCE, UNORDERED_SEQUENCE -> rule.sequence().size();
            case WEIGHTED -> rule.threshold();
            case GROUPS -> rule.perGroup() * (int) definition.inputs().stream()
                    .filter(input -> input.group() != null && view.activeInputs().contains(input.id()))
                    .map(PuzzleDefinition.Input::group)
                    .distinct()
                    .count();
            case ALL -> view.activeInputs().size();
        });
    }

    private static int current(PuzzleDefinition definition, QuestPuzzleService.PuzzleView view, int target) {
        int progress = switch (definition.rule().type()) {
            case SEQUENCE -> view.sequenceProgress().size();
            case WEIGHTED -> view.activeInputs().stream()
                    .filter(view.activated()::containsKey)
                    .map(definition::input)
                    .flatMap(java.util.Optional::stream)
                    .mapToInt(PuzzleDefinition.Input::weight)
                    .sum();
            case STATE_MACHINE -> view.completed() ? 1 : 0;
            default -> (int) view.activeInputs().stream().filter(view.activated()::containsKey).count();
        };
        return Math.min(progress, target);
    }

    private static String hint(PuzzleDefinition definition, int target) {
        return switch (definition.rule().type()) {
            case ALL -> "Every revealed input contributes to the solution.";
            case ANY -> "One revealed input will answer the mechanism.";
            case N_OF_M -> "Find " + target + " inputs that answer the mechanism.";
            case EXACT -> "The mechanism responds only when exactly " + target + " inputs remain active.";
            case SEQUENCE -> "The revealed inputs must be activated in the correct order.";
            case UNORDERED_SEQUENCE -> "All revealed sequence inputs matter; their order does not.";
            case TIMED -> "The mechanism expects " + target + " inputs within a short window.";
            case WEIGHTED -> "Different revealed inputs contribute different strength.";
            case GROUPS -> "Each revealed group must be satisfied.";
            case STATE_MACHINE -> "Each response changes what the mechanism will accept next.";
        };
    }

    private static String status(
            PuzzleDefinition definition,
            int current,
            int target,
            QuestPuzzleService.PuzzleView view) {
        return switch (definition.rule().type()) {
            case STATE_MACHINE -> view.completed() ? "Final state reached" : "Mechanism state updated";
            case GROUPS -> "Grouped pattern in progress";
            case EXACT -> current + " active - exactly " + target + " required";
            case TIMED -> current + " / " + target + " active in the current window";
            case WEIGHTED -> current + " / " + target + " revealed strength";
            default -> current + " / " + target + " progress";
        };
    }

    private static String feedback(Phase phase) {
        return switch (phase) {
            case COMPLETE -> "The puzzle answers.";
            case ACCEPTED -> "The mechanism responds.";
            case MISTAKE -> "The sequence resets.";
            case BLOCKED -> "Nothing happens yet.";
            case ACTIVE -> "";
        };
    }

    private static String humanize(String value) {
        String[] words = value.replace('.', '_').split("_+");
        StringBuilder result = new StringBuilder();
        for (String word : words) {
            if (word.isEmpty()) {
                continue;
            }
            if (!result.isEmpty()) {
                result.append(' ');
            }
            result.append(word.substring(0, 1).toUpperCase(Locale.ROOT));
            result.append(word.substring(1).toLowerCase(Locale.ROOT));
        }
        return result.isEmpty() ? "Puzzle" : result.toString();
    }

    private static String clean(String value, String fallback) {
        String cleaned = Objects.requireNonNullElse(value, "").strip();
        return cleaned.isEmpty() ? fallback : cleaned;
    }
}
