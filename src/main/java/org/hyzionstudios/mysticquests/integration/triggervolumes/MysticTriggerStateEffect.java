package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.Map;

/**
 * {@code mysticquests:trigger_state}: enables, disables or clears a logical override on a volume for
 * the triggering player's session, the player, their party, or everyone.
 *
 * <p>Unlike the engine's own enable and disable effects, which switch a volume for every player, the
 * player, party and session scopes change it only for that audience. It takes effect through the
 * {@code mysticquests:trigger_enabled} condition, so the gated volume must carry that condition.
 */
public final class MysticTriggerStateEffect extends TriggerEffect {
    public static final BuilderCodec<MysticTriggerStateEffect> CODEC = MysticTriggerCodecs.triggerStateEffect();

    private String volume = "";
    private State state = State.disable;
    private Scope scope = Scope.session;

    public String getVolume() {
        return volume == null ? "" : volume;
    }

    public void setVolume(String volume) {
        this.volume = volume;
    }

    public State getState() {
        return state == null ? State.disable : state;
    }

    public void setState(State state) {
        this.state = state;
    }

    public Scope getScope() {
        return scope == null ? Scope.session : scope;
    }

    public void setScope(Scope scope) {
        this.scope = scope;
    }

    @Override
    public void execute(TriggerContext context) {
        Boolean enabled = switch (getState()) {
            case enable -> Boolean.TRUE;
            case disable -> Boolean.FALSE;
            case clear -> null;
        };
        NarrativeTriggerBridge.triggerState(context, getVolume(), getScope().scope, enabled);
    }

    /** Lower-case names keep the volume JSON values readable, matching the other MysticQuests enums. */
    public enum State {
        enable,
        disable,
        clear;

        public static final Map<State, String> DOCUMENT_KEYS = Map.of(enable, "enable", disable, "disable", clear, "clear");
    }

    public enum Scope {
        session(TriggerScope.STORY_SESSION),
        player(TriggerScope.PLAYER),
        party(TriggerScope.PARTY),
        global(TriggerScope.GLOBAL);

        public static final Map<Scope, String> DOCUMENT_KEYS =
                Map.of(session, "session", player, "player", party, "party", global, "global");

        private final TriggerScope scope;

        Scope(TriggerScope scope) {
            this.scope = scope;
        }
    }
}
