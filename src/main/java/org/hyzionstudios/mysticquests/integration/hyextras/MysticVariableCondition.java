package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticVariableCondition extends MysticTriggerCondition {
    public static final BuilderCodec<MysticVariableCondition> CODEC = MysticTriggerCodecs.condition(MysticVariableCondition.class, MysticVariableCondition::new);

    @Override
    public boolean test(TriggerContext context) {
        return maybeInvert(MysticHyExtrasTriggerBridge.variable(context, this));
    }
}
