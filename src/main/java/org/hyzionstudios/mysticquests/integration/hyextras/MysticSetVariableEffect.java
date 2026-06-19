package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticSetVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticSetVariableEffect> CODEC = MysticTriggerCodecs.effect(MysticSetVariableEffect.class, MysticSetVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticHyExtrasTriggerBridge.applyVariable(context, this, MysticHyExtrasTriggerBridge.VariableAction.SET);
    }
}
