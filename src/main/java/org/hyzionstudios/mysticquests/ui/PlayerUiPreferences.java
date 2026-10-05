package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.state.NarrativeStateStore;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import java.util.Locale;
import java.util.UUID;

/**
 * A player's quest UI settings (Redesign Bible §7.4), saved with the player so they survive logout
 * and restarts: how much of the quest tracker shows, and whether quest update pop-ups appear.
 *
 * <p>Stored as reserved variables in the player's story state, like the story audio preferences, so
 * they persist and travel with the player wherever their narrative data does.
 */
public final class PlayerUiPreferences {
    static final NamespacedId TRACKER = NamespacedId.of("mysticquests", "ui.tracker");
    static final NamespacedId POPUPS = NamespacedId.of("mysticquests", "ui.popups");

    private final NarrativeStateStore store;

    public PlayerUiPreferences(NarrativeStateStore store) {
        this.store = store;
    }

    public QuestHudCoordinator.Preference tracker(UUID player) {
        OwnerState state = store.state(ScopeOwner.player(player));
        if (state != null && state.variable(TRACKER) instanceof QuestValue.StringValue(String value)) {
            try {
                return QuestHudCoordinator.Preference.valueOf(value.toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException retired) {
                // A setting from a later version, or one since removed: fall back to the default.
            }
        }
        return QuestHudCoordinator.Preference.AUTOMATIC;
    }

    public void setTracker(UUID player, QuestHudCoordinator.Preference preference) {
        OwnerState state = store.state(ScopeOwner.player(player));
        if (state == null) {
            return;
        }
        if (preference == QuestHudCoordinator.Preference.AUTOMATIC) {
            state.removeVariable(TRACKER);
        } else {
            state.putVariable(TRACKER, new QuestValue.StringValue(preference.name().toLowerCase(Locale.ROOT)));
        }
    }

    /** Whether quest update pop-ups (transition cards) show for this player; on unless they turned them off. */
    public boolean popups(UUID player) {
        OwnerState state = store.state(ScopeOwner.player(player));
        return state == null || !(state.variable(POPUPS) instanceof QuestValue.BoolValue(boolean on)) || on;
    }

    public void setPopups(UUID player, boolean on) {
        OwnerState state = store.state(ScopeOwner.player(player));
        if (state == null) {
            return;
        }
        if (on) {
            state.removeVariable(POPUPS);
        } else {
            state.putVariable(POPUPS, new QuestValue.BoolValue(false));
        }
    }
}
