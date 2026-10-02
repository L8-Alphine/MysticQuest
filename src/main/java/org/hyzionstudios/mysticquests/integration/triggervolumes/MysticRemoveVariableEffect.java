package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticRemoveVariableEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticRemoveVariableEffect> CODEC =
            MysticTriggerCodecs.removeVariableEffect(MysticRemoveVariableEffect.class, MysticRemoveVariableEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyVariable(context, this, MysticTriggerBridge.VariableAction.REMOVE);
    }
}
