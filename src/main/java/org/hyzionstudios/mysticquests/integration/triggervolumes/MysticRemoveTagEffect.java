package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticRemoveTagEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticRemoveTagEffect> CODEC =
            MysticTriggerCodecs.tagEffect(MysticRemoveTagEffect.class, MysticRemoveTagEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyTag(context, this, false);
    }
}
