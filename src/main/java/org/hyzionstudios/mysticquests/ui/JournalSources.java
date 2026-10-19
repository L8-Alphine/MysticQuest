package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.QuestResult;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

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

    /**
     * A quest another mod runs, mirrored into the Journal (docs/integrations/external-quests.md).
     *
     * @param entry the quest under its {@code ext:} key
     * @param source the mod's name, for the card's context line
     */
    record Mirrored(JournalEntry entry, String category, String rewardText, String source) {
    }

    /**
     * A mirrored quest the player has finished, for the Journal's completed section.
     *
     * @param quest the finished quest under its {@code ext:} key
     * @param at when they finished it, or null when the mod does not record that
     */
    record MirroredCompletion(Mirrored quest, @Nullable Instant at) {
    }

    List<Story> stories(UUID player);

    /** Mirrored quests in progress, mod by mod. */
    default List<Mirrored> mirrored(UUID player) {
        return List.of();
    }

    /** Mirrored quests the player has finished and is not doing again, mod by mod. */
    default List<MirroredCompletion> mirroredCompleted(UUID player) {
        return List.of();
    }

    /**
     * The mirrored quest the tracker follows, or null when it follows the player's own.
     *
     * @param ownTracked whether the player tracks one of their MysticQuests quests
     */
    @Nullable
    default String trackedMirrored(UUID player, boolean ownTracked) {
        return null;
    }

    /** Track, untrack or abandon a mirrored quest, in the mod that runs it. On the player's world thread. */
    default QuestResult mirroredAction(Ref<EntityStore> ref, Store<EntityStore> store, String key, String action) {
        return QuestResult.failure("That quest is not available right now.");
    }

    /** The player picked one of their own quests for the tracker, over any mirrored one. */
    default void trackOwnQuest(UUID player) {
    }

    /**
     * Another mod's own quest book, offered as a tab on the quest pages.
     *
     * @param id the mod's source id, as in {@code ext:<id>:...} quest keys
     */
    record ModBook(String id, String label) {
    }

    /** The quest books of the mods whose quests are mirrored, for the tabs. */
    default List<ModBook> modBooks() {
        return List.of();
    }

    /** Opens a mod's own quest book over the current page; false when it could not. On the player's world thread. */
    default boolean openModBook(Ref<EntityStore> ref, Store<EntityStore> store, String id) {
        return false;
    }

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
