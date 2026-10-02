package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticAddTagEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticAddTagEffect> CODEC =
            MysticTriggerCodecs.tagEffect(MysticAddTagEffect.class, MysticAddTagEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyTag(context, this, true);
    }
}
