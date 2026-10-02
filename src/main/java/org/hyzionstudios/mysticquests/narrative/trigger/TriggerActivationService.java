package org.hyzionstudios.mysticquests.narrative.trigger;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.StateHost;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The logical activation layer over Hytale trigger volumes (§5.2).
 *
 * <p>The engine can only enable or disable a volume for everyone ({@code VolumeEntry#setEnabled}).
 * Per-player, per-party and per-session activation is therefore held here, as overrides in the
 * owning state, and consulted when a volume fires: by the {@code mysticquests:trigger_enabled}
 * condition on the volume itself, and by MysticQuests' own handling of the volume's events. The
 * native volume stays enabled for everyone, and each audience sees its own logical state.
 *
 * <p>Overrides persist with their owner: global ones with server state, player and party ones with
 * those owners, session ones inside the session. A disabled key-search volume therefore stays
 * disabled for that party across restarts.
 */
public final class TriggerActivationService {
    /** Which level decided a volume's state for one player, for the debugger. */
    public record Decision(boolean enabled, @Nullable TriggerScope decidedBy, @Nullable String owner) {
    }

    private final StateHost host;
    private final String serverId;
    private final Function<UUID, Optional<String>> partyOf;
    private final Function<UUID, List<String>> activeSessionsOf;

    /**
     * @param activeSessionsOf ids of the active sessions a player takes part in: their own and their
     *         party's
     */
    public TriggerActivationService(
            StateHost host,
            String serverId,
            Function<UUID, Optional<String>> partyOf,
            Function<UUID, List<String>> activeSessionsOf) {
        this.host = host;
        this.serverId = serverId;
        this.partyOf = partyOf;
        this.activeSessionsOf = activeSessionsOf;
    }

    /** Whether {@code volumeKey} is logically enabled for {@code player}; non-players only see global. */
    public boolean isEnabled(String volumeKey, @Nullable UUID player) {
        return decide(volumeKey, player).enabled();
    }

    /**
     * Resolves the most specific override. Sessions are checked first. If a player takes part in
     * several sessions that disagree about one volume, disabled wins: a story that explicitly
     * switched a volume off should not be overridden by an unrelated story.
     */
    public Decision decide(String volumeKey, @Nullable UUID player) {
        if (player != null) {
            Boolean sessionOverride = null;
            String decidingSession = null;
            for (String sessionId : activeSessionsOf.apply(player)) {
                Boolean override = override(ScopeOwner.session(sessionId), volumeKey);
                if (override != null && (sessionOverride == null || !override)) {
                    sessionOverride = override;
                    decidingSession = sessionId;
                }
            }
            if (sessionOverride != null) {
                return new Decision(sessionOverride, TriggerScope.STORY_SESSION, decidingSession);
            }
            Boolean playerOverride = override(ScopeOwner.player(player), volumeKey);
            if (playerOverride != null) {
                return new Decision(playerOverride, TriggerScope.PLAYER, player.toString());
            }
            Optional<String> party = partyOf.apply(player);
            if (party.isPresent()) {
                Boolean partyOverride = override(new ScopeOwner(VariableScope.PARTY, party.get()), volumeKey);
                if (partyOverride != null) {
                    return new Decision(partyOverride, TriggerScope.PARTY, party.get());
                }
            }
        }
        Boolean global = override(serverOwner(), volumeKey);
        return global != null
                ? new Decision(global, TriggerScope.GLOBAL, serverId)
                : new Decision(true, null, null);
    }

    /**
     * Sets or clears an override at one level.
     *
     * @param enabled true to enable, false to disable, null to clear and fall back to the next level
     */
    public StateResult set(TriggerScope scope, String volumeKey, @Nullable Boolean enabled, ScopeContext context) {
        if (volumeKey == null || volumeKey.isBlank()) {
            return StateResult.rejected(DiagnosticCode.UNKNOWN_TRIGGER_VOLUME, "no trigger volume named");
        }
        ScopeOwner owner = switch (scope) {
            case GLOBAL -> serverOwner();
            case PLAYER -> context.actor() == null ? null : ScopeOwner.player(context.actor());
            case PARTY -> context.partyId() == null ? null : new ScopeOwner(VariableScope.PARTY, context.partyId());
            case STORY_SESSION -> context.sessionId() == null ? null : ScopeOwner.session(context.sessionId());
        };
        if (owner == null) {
            return StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED,
                    "trigger " + volumeKey + ": " + scope + " scope has no owner here");
        }
        OwnerState state = host.state(owner);
        if (state == null) {
            return StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED, "trigger " + volumeKey + ": " + owner + " is not open");
        }
        return state.setTriggerOverride(volumeKey, enabled) ? StateResult.changed(null) : StateResult.unchanged(null);
    }

    @Nullable
    private Boolean override(ScopeOwner owner, String volumeKey) {
        OwnerState state = host.state(owner);
        return state == null ? null : state.triggerOverride(volumeKey);
    }

    private ScopeOwner serverOwner() {
        return new ScopeOwner(VariableScope.SERVER, serverId);
    }
}
