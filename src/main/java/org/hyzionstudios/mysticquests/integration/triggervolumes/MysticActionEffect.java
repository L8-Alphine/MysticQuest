package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.codec.builder.BuilderCodec;

/**
 * Runs any MysticQuests event type from a trigger volume.
 *
 * <p>The other effects in this package each hard-code one action, which means every new event type
 * would need a new class, a new codec, and a new registration before world builders could reach it.
 * This one carries the event type as a field instead, so volumes automatically get every built-in
 * event — {@code hidePlayer}, {@code preventTargeting}, {@code sendTitle}, {@code setCamera} — and
 * every event another mod registers through {@code MysticQuestsRegistry}, with no further plumbing.
 *
 * <pre>
 *   { "Type": "mysticquests:action", "Action": "hidePlayer", "Target": "nearest:12" }
 *   { "Type": "mysticquests:action", "Action": "mymod:grant_skill", "Value": "mining" }
 * </pre>
 *
 * <p>The event is dispatched through the same path quests use, so an action behaves identically
 * whether a quest step or a volume fired it.
 */
public final class MysticActionEffect extends MysticTriggerEffect {
    public static final BuilderCodec<MysticActionEffect> CODEC =
            MysticTriggerCodecs.action(MysticActionEffect.class, MysticActionEffect::new);

    private String action;
    private String viewer;
    private String mode;
    private String message;
    private String subtitle;
    private Boolean locked;

    public String getAction() {
        return action == null ? "" : action;
    }

    public void setAction(String action) {
        this.action = action;
    }

    public String getViewer() {
        return viewer == null ? "" : viewer;
    }

    public void setViewer(String viewer) {
        this.viewer = viewer;
    }

    public String getMode() {
        return mode == null ? "" : mode;
    }

    public void setMode(String mode) {
        this.mode = mode;
    }

    public String getMessage() {
        return message == null ? "" : message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSubtitle() {
        return subtitle == null ? "" : subtitle;
    }

    public void setSubtitle(String subtitle) {
        this.subtitle = subtitle;
    }

    public Boolean getLocked() {
        return locked;
    }

    public void setLocked(Boolean locked) {
        this.locked = locked;
    }

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.runAction(context, this);
    }
}
