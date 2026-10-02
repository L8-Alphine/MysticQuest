package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticHasTagCondition extends MysticTriggerCondition {
    public static final BuilderCodec<MysticHasTagCondition> CODEC =
            MysticTriggerCodecs.tagCondition(MysticHasTagCondition.class, MysticHasTagCondition::new);

    @Override
    public boolean test(TriggerContext context) {
        return maybeInvert(MysticTriggerBridge.hasTag(context, this));
    }
}
