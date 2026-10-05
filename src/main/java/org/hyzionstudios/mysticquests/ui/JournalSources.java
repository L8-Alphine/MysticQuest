package org.hyzionstudios.mysticquests.ui;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What the Journal shows besides v1 quests: the player's 2.0 stories, and their quest UI settings
 * (Redesign Bible §7.1, §7.4). Supplied by the runtime; {@link #NONE} when the story runtime is off.
 */
public interface JournalSources {
    /** A milestone the player reached in a story, in their words, with when. */
    record Milestone(String text, Instant reached) {
    }

    /**
     * A story the player is part of.
     *
     * @param party whether it belongs to their party rather than to them alone
     * @param milestones milestones in this story, newest first; never hidden future steps
     */
    record Story(String key, String name, boolean party, Instant since, List<Milestone> milestones) {
    }

    /**
     * @param voiceLocale the chosen voice language, or null when it follows the game
     * @param popupsAvailable whether the server shows quest update pop-ups at all
     */
    record Settings(QuestHudCoordinator.Preference tracker, boolean subtitles, @Nullable String voiceLocale,
                    boolean popups, boolean popupsAvailable) {
    }

    List<Story> stories(UUID player);

    Settings settings(UUID player);

    void setTracker(UUID player, QuestHudCoordinator.Preference preference);

    void setSubtitles(UUID player, boolean on);

    /** Voice lines follow the game language again. */
    void followGameLanguage(UUID player);

    void setPopups(UUID player, boolean on);

    JournalSources NONE = new JournalSources() {
        @Override
        public List<Story> stories(UUID player) {
            return List.of();
        }

        @Override
        public Settings settings(UUID player) {
            return new Settings(QuestHudCoordinator.Preference.AUTOMATIC, true, null, true, false);
        }

        @Override
        public void setTracker(UUID player, QuestHudCoordinator.Preference preference) {
        }

        @Override
        public void setSubtitles(UUID player, boolean on) {
        }

        @Override
        public void followGameLanguage(UUID player) {
        }

        @Override
        public void setPopups(UUID player, boolean on) {
        }
    };
}
