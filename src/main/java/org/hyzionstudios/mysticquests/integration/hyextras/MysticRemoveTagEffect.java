package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticRemoveTagEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticRemoveTagEffect> CODEC = MysticTriggerCodecs.effect(MysticRemoveTagEffect.class, MysticRemoveTagEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticHyExtrasTriggerBridge.applyTag(context, this, false);
    }
}
