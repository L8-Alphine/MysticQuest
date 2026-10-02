package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticIncrementVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticIncrementVariableEffect> CODEC =
            MysticTriggerCodecs.incrementVariableEffect(
                    MysticIncrementVariableEffect.class, MysticIncrementVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyVariable(context, this, MysticTriggerBridge.VariableAction.INCREMENT);
    }
}
