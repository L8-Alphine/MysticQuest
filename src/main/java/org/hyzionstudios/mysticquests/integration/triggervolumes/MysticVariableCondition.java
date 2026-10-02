package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticVariableCondition extends MysticTriggerCondition {
    public static final BuilderCodec<MysticVariableCondition> CODEC =
            MysticTriggerCodecs.variableCondition(MysticVariableCondition.class, MysticVariableCondition::new);

    @Override
    public boolean test(TriggerContext context) {
        return maybeInvert(MysticTriggerBridge.variable(context, this));
    }
}
