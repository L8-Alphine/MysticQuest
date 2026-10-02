package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * {@code mysticquests:puzzle_input}: feeds a puzzle input for the triggering player's audience.
 *
 * <p>An alternative to binding the input to the volume in the puzzle definition. Use it when the
 * volume's event, interval or delay should decide when the input fires. With {@code Release} set, a
 * toggleable input is released instead, for example on {@code EXIT} from a pressure plate.
 */
public final class MysticPuzzleInputEffect extends TriggerEffect {
    public static final BuilderCodec<MysticPuzzleInputEffect> CODEC = MysticTriggerCodecs.puzzleInputEffect();

    private String puzzle = "";
    private String input = "";
    private Boolean release;

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

    public Boolean getRelease() {
        return release;
    }

    public void setRelease(Boolean release) {
        this.release = release;
    }

    @Override
    public void execute(TriggerContext context) {
        NarrativeTriggerBridge.puzzleInput(context, getPuzzle(), getInput(), Boolean.TRUE.equals(release));
    }
}
