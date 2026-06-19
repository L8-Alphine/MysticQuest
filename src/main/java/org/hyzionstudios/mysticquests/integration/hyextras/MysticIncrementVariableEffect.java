package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticIncrementVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticIncrementVariableEffect> CODEC = MysticTriggerCodecs.effect(MysticIncrementVariableEffect.class, MysticIncrementVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticHyExtrasTriggerBridge.applyVariable(context, this, MysticHyExtrasTriggerBridge.VariableAction.INCREMENT);
    }
}
