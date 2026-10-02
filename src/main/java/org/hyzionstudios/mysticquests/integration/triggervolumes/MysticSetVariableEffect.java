package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticSetVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticSetVariableEffect> CODEC =
            MysticTriggerCodecs.setVariableEffect(MysticSetVariableEffect.class, MysticSetVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyVariable(context, this, MysticTriggerBridge.VariableAction.SET);
    }
}
