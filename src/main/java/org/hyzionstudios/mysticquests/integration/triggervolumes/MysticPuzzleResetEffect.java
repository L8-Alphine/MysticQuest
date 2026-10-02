package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * {@code mysticquests:puzzle_reset}: starts a new round of a puzzle for the triggering player's
 * audience, for example from a "start over" lever. {@code Reroll} also draws a new random selection.
 */
public final class MysticPuzzleResetEffect extends TriggerEffect {
    public static final BuilderCodec<MysticPuzzleResetEffect> CODEC = MysticTriggerCodecs.puzzleResetEffect();

    private String puzzle = "";
    private Boolean reroll;

    public String getPuzzle() {
        return puzzle == null ? "" : puzzle;
    }

    public void setPuzzle(String puzzle) {
        this.puzzle = puzzle;
    }

    public Boolean getReroll() {
        return reroll;
    }

    public void setReroll(Boolean reroll) {
        this.reroll = reroll;
    }

    @Override
    public void execute(TriggerContext context) {
        NarrativeTriggerBridge.puzzleReset(context, getPuzzle(), Boolean.TRUE.equals(reroll));
    }
}
