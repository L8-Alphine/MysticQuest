package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * {@code mysticquests:puzzle_input_available}: passes when a puzzle input still matters to the
 * triggering player's audience. It must be selected for them, not yet activated, and the puzzle
 * unsolved.
 *
 * <p>For a four-of-ten key hunt, put this on each key volume. Its particles and sounds then play only
 * for players whose selection includes that key, and stop once they pick it up. The six unselected
 * locations stay inert for that audience (§10.1).
 */
public final class MysticPuzzleInputAvailableCondition extends TriggerCondition {
    public static final BuilderCodec<MysticPuzzleInputAvailableCondition> CODEC = MysticTriggerCodecs.puzzleInputCondition();

    private String puzzle = "";
    private String input = "";
    private Boolean invert;

    public String getPuzzle() {
        return puzzle == null ? "" : puzzle;
    }

    public void setPuzzle(String puzzle) {
        this.puzzle = puzzle;
    }

    public String getInput() {
        return input == null ? "" : input;
    }

    public void setInput(String input) {
        this.input = input;
    }

    public Boolean getInvert() {
        return invert;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }

    @Override
    public boolean test(TriggerContext context) {
        boolean available = NarrativeTriggerBridge.inputAvailable(context, getPuzzle(), getInput());
        return Boolean.TRUE.equals(invert) ? !available : available;
    }
}
