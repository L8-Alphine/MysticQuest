package org.hyzionstudios.mysticquests.ui;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Decides which MysticQuests HUD layers a player sees, and in which composition.
 *
 * <p>The coordinator is intentionally independent of quest progression. Other gameplay systems can
 * publish screen pressure or cinematic state without mutating the quest snapshot, while player
 * preference remains an explicit override. This is the local MysticQuests boundary that a shared
 * cross-plugin HUD region manager can replace later.
 *
 * <p>Two layers, two screen regions: the tracker reserves the top-right, the puzzle card the
 * bottom-centre above the hotbar. Each is its own HUD document, because one layout tree cannot place
 * blocks in two corners.
 */
public final class QuestHudCoordinator {
    public enum Preference {
        AUTOMATIC,
        COMPACT,
        EXPANDED,
        /** No tracker at all; a puzzle card still shows, since it is the puzzle's only feedback. */
        HIDDEN
    }

    public record Context(boolean cinematic, boolean screenPressure) {
        public static final Context EXPLORATION = new Context(false, false);
    }

    /**
     * @param mode the tracker composition; meaningful only while {@code tracker} is true
     * @param tracker whether the top-right tracker layer is shown
     * @param puzzle whether the bottom-centre puzzle layer is shown
     */
    public record Placement(QuestHudViewModel.DisplayMode mode, boolean tracker, boolean puzzle) {
    }

    private final Map<UUID, Preference> preferences = new ConcurrentHashMap<>();
    private final Map<UUID, Context> contexts = new ConcurrentHashMap<>();
    /**
     * Named reasons the screen is cinematic, such as a story cutscene. Kept apart from
     * {@link #setContext} so that a conversation ending mid-scene cannot bring the HUD back early.
     */
    private final Map<UUID, Set<String>> cinematicSources = new ConcurrentHashMap<>();

    /**
     * @param model the tracked quest, or null when the player tracks nothing
     * @param puzzleActive whether the server holds a puzzle card for the player
     */
    public Placement resolve(UUID playerId, @Nullable QuestHudViewModel model, boolean puzzleActive) {
        Context context = contexts.getOrDefault(playerId, Context.EXPLORATION);
        if (context.cinematic() || !cinematicSources.getOrDefault(playerId, Set.of()).isEmpty()) {
            // A conversation owns the screen; nothing here is a protected timer.
            return new Placement(QuestHudViewModel.DisplayMode.COMPACT, false, false);
        }
        Preference preference = preferences.getOrDefault(playerId, Preference.AUTOMATIC);
        boolean tracker = model != null && preference != Preference.HIDDEN;
        if (puzzleActive || context.screenPressure()) {
            // An active puzzle is the player's focus: the tracker steps down to its compact form.
            return new Placement(QuestHudViewModel.DisplayMode.COMPACT, tracker, puzzleActive);
        }

        QuestHudViewModel.DisplayMode mode = switch (preference) {
            case COMPACT, HIDDEN -> QuestHudViewModel.DisplayMode.COMPACT;
            case EXPANDED -> QuestHudViewModel.DisplayMode.EXPANDED;
            case AUTOMATIC -> model == null || model.supportingObjectives().isEmpty()
                    ? QuestHudViewModel.DisplayMode.COMPACT
                    : QuestHudViewModel.DisplayMode.EXPANDED;
        };
        return new Placement(mode, tracker, false);
    }

    public void setPreference(UUID playerId, Preference preference) {
        if (preference == Preference.AUTOMATIC) {
            preferences.remove(playerId);
        } else {
            preferences.put(playerId, preference);
        }
    }

    public Preference preference(UUID playerId) {
        return preferences.getOrDefault(playerId, Preference.AUTOMATIC);
    }

    public void setContext(UUID playerId, Context context) {
        if (Context.EXPLORATION.equals(context)) {
            contexts.remove(playerId);
        } else {
            contexts.put(playerId, context);
        }
    }

    /** Adds or removes one named reason for the cinematic composition; the HUD returns when none remain. */
    public void setCinematic(UUID playerId, String source, boolean active) {
        if (active) {
            cinematicSources.computeIfAbsent(playerId, ignored -> ConcurrentHashMap.newKeySet()).add(source);
        } else {
            cinematicSources.computeIfPresent(playerId, (ignored, sources) -> {
                sources.remove(source);
                return sources.isEmpty() ? null : sources;
            });
        }
    }

    public void forget(UUID playerId) {
        preferences.remove(playerId);
        contexts.remove(playerId);
        cinematicSources.remove(playerId);
    }

    public void clear() {
        preferences.clear();
        contexts.clear();
        cinematicSources.clear();
    }
}
