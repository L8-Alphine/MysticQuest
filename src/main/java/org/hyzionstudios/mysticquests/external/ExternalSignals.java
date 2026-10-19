package org.hyzionstudios.mysticquests.external;

import java.util.ArrayList;
import java.util.List;

/**
 * The MysticQuests signals a mirrored change stands for, so {@code signal} objectives can count what
 * happens in other mods. Every moment is published twice: once naming the thing and once without,
 * so content can ask for "this quest" or for "any quest":
 *
 * <pre>
 *   endgame:quest_accepted:story_echo     endgame:quest_accepted
 *   endgame:quest_completed:story_echo    endgame:quest_completed
 *   ziggfreed:stat:level.mining           ziggfreed:stat        (amount = how much it rose)
 * </pre>
 *
 * A player's first snapshot after joining is a baseline and publishes nothing, so logging in never
 * replays history.
 */
public final class ExternalSignals {
    /** One signal to publish: what an objective's {@code signal} names, and how much it counts. */
    public record Signal(String name, int amount) {
    }

    private ExternalSignals() {
    }

    public static List<Signal> of(ExternalQuestBook.Change change) {
        if (change.baseline()) {
            return List.of();
        }
        String source = change.after().source();
        List<Signal> signals = new ArrayList<>();
        for (String id : change.newlyActive()) {
            pair(signals, source + ":quest_accepted", id, 1);
        }
        for (String id : change.newlyCompleted()) {
            pair(signals, source + ":quest_completed", id, 1);
        }
        change.statGains().forEach((stat, gain) -> pair(signals, source + ":stat", stat, (int) Math.min(Integer.MAX_VALUE, gain)));
        return signals;
    }

    /** A specific and a generic signal for one moment. */
    public static void pair(List<Signal> signals, String moment, String subject, int amount) {
        if (amount <= 0) {
            return;
        }
        signals.add(new Signal(moment + ":" + normalise(subject), amount));
        signals.add(new Signal(moment, amount));
    }

    /**
     * Signal names are matched exactly, so the subject keeps the other mod's own spelling — an author
     * copies the id from that mod's files — and only loses surrounding and inner spaces.
     */
    public static String normalise(String subject) {
        return subject == null ? "" : subject.strip().replace(' ', '_');
    }
}
