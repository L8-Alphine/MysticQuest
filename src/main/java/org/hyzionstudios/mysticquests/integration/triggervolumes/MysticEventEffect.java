package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

public final class MysticEventEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticEventEffect> CODEC =
            MysticTriggerCodecs.eventEffect(MysticEventEffect.class, MysticEventEffect::new);

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.applyEvent(context, this);
    }
}
