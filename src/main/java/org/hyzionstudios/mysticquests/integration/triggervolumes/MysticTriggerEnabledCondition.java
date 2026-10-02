package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * {@code mysticquests:trigger_enabled}: passes when the volume is logically enabled for the triggering
 * player (§5.2).
 *
 * <p>This is how a native volume becomes per-player without being toggled for everyone. Add the
 * condition, and a session, player or party disable from MysticQuests stops the volume's own
 * effects for that audience; its rejection effects run instead. {@code Volume} checks another
 * volume's state, which lets one controller volume gate several. Leave it blank for this volume.
 */
public final class MysticTriggerEnabledCondition extends TriggerCondition {
    public static final BuilderCodec<MysticTriggerEnabledCondition> CODEC = MysticTriggerCodecs.triggerEnabledCondition();

    private String volume = "";
    private Boolean invert;

    public String getVolume() {
        return volume == null ? "" : volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public Boolean getInvert() {
        return invert;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }

    @Override
    public boolean test(TriggerContext context) {
        boolean enabled = NarrativeTriggerBridge.enabled(context, getVolume());
        return Boolean.TRUE.equals(invert) ? !enabled : enabled;
    }
}
