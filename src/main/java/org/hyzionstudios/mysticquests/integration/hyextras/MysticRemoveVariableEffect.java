package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticRemoveVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticRemoveVariableEffect> CODEC = MysticTriggerCodecs.effect(MysticRemoveVariableEffect.class, MysticRemoveVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticHyExtrasTriggerBridge.applyVariable(context, this, MysticHyExtrasTriggerBridge.VariableAction.REMOVE);
    }
}
