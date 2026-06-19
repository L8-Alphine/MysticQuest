package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticAddTagEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticAddTagEffect> CODEC = MysticTriggerCodecs.effect(MysticAddTagEffect.class, MysticAddTagEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticHyExtrasTriggerBridge.applyTag(context, this, true);
    }
}
