package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** Redesign Bible §7.4: quest UI settings are the player's, and they survive logout and restarts. */
final class PlayerUiPreferencesTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void settingsAreSavedWithThePlayer() {
        PlayerUiPreferences preferences = new PlayerUiPreferences(kit.runtime().store());
        assertEquals(QuestHudCoordinator.Preference.AUTOMATIC, preferences.tracker(ALICE), "automatic until chosen");
        assertTrue(preferences.popups(ALICE));

        preferences.setTracker(ALICE, QuestHudCoordinator.Preference.HIDDEN);
        preferences.setPopups(ALICE, false);
        kit.runtime().onQuit(ALICE, java.util.Optional.empty(), false);
        kit.restart();

        PlayerUiPreferences again = new PlayerUiPreferences(kit.runtime().store());
        assertEquals(QuestHudCoordinator.Preference.HIDDEN, again.tracker(ALICE));
        assertFalse(again.popups(ALICE));

        again.setTracker(ALICE, QuestHudCoordinator.Preference.AUTOMATIC);
        assertEquals(QuestHudCoordinator.Preference.AUTOMATIC, again.tracker(ALICE), "automatic clears the saved choice");
    }
}
