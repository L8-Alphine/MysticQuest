package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.ObjectiveMarker;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class QuestHudFocusTest {
    @Test
    void aPointMarkerNamesItsPlaceOnTheMapAndInTheGuidance() {
        QuestHudFocus focus = QuestHudFocus.of(
                new ObjectiveMarker("Orbis", 120.5D, 64.0D, -88.0D, "Guardian Statue", false, ""),
                "Speak to the guardian");

        assertTrue(focus.hasWorldPosition());
        assertEquals(QuestHudFocus.TargetKind.POINT_TARGET, focus.targetKind());
        assertEquals("Guardian Statue", focus.markerLabel());
        assertEquals("Marked on your map: Guardian Statue", focus.guidanceLabel());
        assertEquals(ObjectiveMarker.DEFAULT_ICON, focus.icon());
    }

    @Test
    void aSearchAreaDoesNotPromiseAnExactSpot() {
        QuestHudFocus focus = QuestHudFocus.of(
                new ObjectiveMarker("Orbis", 1, 2, 3, "Western Druid Ruins", true, ""),
                "Find the hidden keys");

        assertEquals(QuestHudFocus.TargetKind.SEARCH_AREA, focus.targetKind());
        assertEquals("Search: Western Druid Ruins", focus.markerLabel());
        assertEquals("Search the area marked on your map", focus.guidanceLabel());
    }

    @Test
    void anUnlabelledMarkerTakesTheObjectiveName() {
        QuestHudFocus focus = QuestHudFocus.of(
                new ObjectiveMarker("Orbis", 1, 2, 3, "", false, "Portal.png"),
                "Visit the Vote Crates");

        assertEquals("Visit the Vote Crates", focus.markerLabel());
        assertEquals("Portal.png", focus.icon());
    }

    @Test
    void aTargetWithoutAWorldIsNeverPublished() {
        QuestHudFocus focus = new QuestHudFocus(
                QuestHudFocus.TargetKind.POINT_TARGET, "", "Somewhere", 1, 2, 3, "");

        assertFalse(focus.hasWorldPosition());
        assertEquals("Somewhere", focus.markerLabel());
    }
}
