package org.hyzionstudios.mysticquests.integration.worldevents;

import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker.PlayerPosition;
import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker.Site;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Hytale's world events never say which player a breach concerns; the tracker decides it from who
 * came near the breach's site. These pin those rules, which need a live server to see otherwise.
 */
final class WorldEventTrackerTest {
    private static final String WORLD = "default";
    private static final String BREACH = "Goblin_Breach_Prelude_Event";
    private static final Site SITE = new Site(100, 64, 100);

    private final WorldEventTracker tracker = new WorldEventTracker();
    private final List<String> emitted = new ArrayList<>();
    private final WorldEventTracker.Emitter emitter =
            (player, type, asset, world) -> emitted.add(type + ":" + asset + ":" + player);

    private final UUID event = UUID.randomUUID();
    private final UUID near = UUID.randomUUID();
    private final UUID watching = UUID.randomUUID();
    private final UUID far = UUID.randomUUID();

    @Test
    void aBreachIsDetectedOncePerPlayerPerSite() {
        tracker.started(WORLD, event, BREACH);
        List<PlayerPosition> players = List.of(at(near, 10), at(far, 400));

        tracker.observe(WORLD, event, BREACH, SITE, players, emitter);
        tracker.observe(WORLD, event, BREACH, SITE, players, emitter);

        assertEquals(List.of("worldEventDetected:" + BREACH + ":" + near), emitted);
    }

    @Test
    void aBreachThatMovesCanBeDetectedAgain() {
        tracker.observe(WORLD, event, BREACH, SITE, List.of(at(near, 10)), emitter);
        Site moved = new Site(500, 64, 500);
        tracker.observe(WORLD, event, BREACH, moved, List.of(new PlayerPosition(near, 505, 64, 505)), emitter);

        assertEquals(2, emitted.size());
    }

    @Test
    void completionCreditsOnlyThoseWhoCameClose() {
        tracker.started(WORLD, event, BREACH);
        // Detection reaches farther than participation: watching the beam is not fighting the pack.
        tracker.observe(WORLD, event, BREACH, SITE, List.of(at(near, 10), at(watching, 80), at(far, 400)), emitter);
        emitted.clear();

        tracker.ended(WORLD, event, BREACH, false, emitter);

        assertEquals(List.of("worldEventCompleted:" + BREACH + ":" + near), emitted);
        assertFalse(tracker.active(WORLD, BREACH), "a finished event is forgotten");
    }

    @Test
    void anIterationCreditsItsParticipantsEvenAfterItsSiteWasCleared() {
        tracker.started(WORLD, event, BREACH);
        tracker.observe(WORLD, event, BREACH, SITE, List.of(at(near, 5)), emitter);
        // The restart clears the event's context before the end is reported.
        tracker.observe(WORLD, event, BREACH, null, List.of(at(near, 5)), emitter);
        emitted.clear();

        tracker.ended(WORLD, event, BREACH, true, emitter);

        assertEquals(List.of("worldEventCompleted:" + BREACH + ":" + near), emitted);
        assertTrue(tracker.active(WORLD, BREACH), "an iteration restarts; the event keeps running");
        emitted.clear();
        tracker.ended(WORLD, event, BREACH, true, emitter);
        assertTrue(emitted.isEmpty(), "each iteration credits only its own participants");
    }

    @Test
    void anEventThatNeverPlacedASiteCreditsNobody() {
        tracker.started(WORLD, event, BREACH);
        tracker.observe(WORLD, event, BREACH, null, List.of(at(near, 0)), emitter);

        tracker.ended(WORLD, event, BREACH, false, emitter);

        assertTrue(emitted.isEmpty());
    }

    @Test
    void activityIsPerWorldAndPerAsset() {
        tracker.started(WORLD, event, BREACH);

        assertTrue(tracker.active(WORLD, BREACH));
        assertFalse(tracker.active("other", BREACH));
        assertTrue(tracker.activeAnywhere(BREACH));
        assertFalse(tracker.activeAnywhere("Goblin_Breach_First_Event"));

        tracker.removed(WORLD, event);
        assertFalse(tracker.activeAnywhere(BREACH));
    }

    private static PlayerPosition at(UUID player, double blocksAway) {
        return new PlayerPosition(player, SITE.x() + 0.5 + blocksAway, SITE.y(), SITE.z() + 0.5);
    }
}
