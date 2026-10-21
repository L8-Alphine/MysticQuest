package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.protocol.GameMode;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class PreviewSpoofTest {

    @Test
    void anAdventurePlayerIsToldCreativeOnceAndGetsAdventureBack() {
        PreviewSpoof spoof = new PreviewSpoof();

        assertEquals(GameMode.Creative, spoof.beforeSend(GameMode.Adventure));
        assertNull(spoof.beforeSend(GameMode.Adventure), "The retries do not re-send the spoof");
        assertTrue(spoof.active());
        assertEquals(GameMode.Adventure, spoof.end(GameMode.Adventure));
        assertFalse(spoof.active());
        assertNull(spoof.end(GameMode.Adventure), "Restoring twice sends nothing");
    }

    @Test
    void aCreativePlayerIsNeverSpoofed() {
        PreviewSpoof spoof = new PreviewSpoof();

        assertNull(spoof.beforeSend(GameMode.Creative));
        assertFalse(spoof.active());
        assertNull(spoof.end(GameMode.Creative), "Nothing to hand back");
    }

    @Test
    void restoringUsesTheCurrentRealModeNotTheRememberedOne() {
        PreviewSpoof spoof = new PreviewSpoof();
        spoof.beforeSend(GameMode.Adventure);

        // An admin switched the player to Creative while the card was open.
        assertEquals(GameMode.Creative, spoof.end(GameMode.Creative));
    }

    @Test
    void theRememberedModeIsUsedWhenTheCurrentOneCannotBeRead() {
        PreviewSpoof spoof = new PreviewSpoof();
        spoof.beforeSend(GameMode.Adventure);

        assertEquals(GameMode.Adventure, spoof.end(null));
    }

    @Test
    void becomingReallyCreativeEndsTheSpoof() {
        PreviewSpoof spoof = new PreviewSpoof();
        spoof.beforeSend(GameMode.Adventure);

        assertNull(spoof.beforeSend(GameMode.Creative));
        assertFalse(spoof.active(), "The client is really in Creative now; there is nothing to restore");
    }

    @Test
    void unreadableModeSendsNothing() {
        PreviewSpoof spoof = new PreviewSpoof();

        assertNull(spoof.beforeSend(null));
        assertFalse(spoof.active());
    }
}
