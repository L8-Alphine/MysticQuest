package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * {@code mysticquests:cutscene_play}: starts a story cutscene for the triggering player's audience,
 * for example when they step into the temple. The scene's own story session is used, so entering
 * the volume again while the scene runs restarts nothing it has already settled.
 */
public final class MysticCutscenePlayEffect extends TriggerEffect {
    public static final BuilderCodec<MysticCutscenePlayEffect> CODEC = MysticTriggerCodecs.cutscenePlayEffect();

    private String cutscene = "";

    public String getCutscene() {
        return cutscene == null ? "" : cutscene;
    }

    public void setCutscene(String cutscene) {
        this.cutscene = cutscene;
    }

    @Override
    public void execute(TriggerContext context) {
        NarrativeTriggerBridge.cutscenePlay(context, getCutscene());
    }
}
