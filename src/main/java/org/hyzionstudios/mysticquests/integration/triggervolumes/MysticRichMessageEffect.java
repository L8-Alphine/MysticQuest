package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.Map;

/** Sends placeholder-aware, color-formatted chat to the triggering player or every online player. */
public final class MysticRichMessageEffect extends TriggerEffect {
    public static final BuilderCodec<MysticRichMessageEffect> CODEC = MysticTriggerCodecs.richMessageEffect();

    private String message = "";
    private Audience audience = Audience.player;
    private String packageId = "";

    public String getMessage() {
        return message == null ? "" : message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public Audience getAudience() {
        return audience == null ? Audience.player : audience;
    }

    public void setAudience(Audience audience) {
        this.audience = audience;
    }

    public String getPackageId() {
        return packageId == null ? "" : packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.sendRichMessage(context, this);
    }

    public enum Audience {
        player,
        global;

        public static final Map<Audience, String> ALIASES = Map.of(
                player, "player",
                global, "global");
    }
}
