package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the bookkeeping half of visibility, which is what decides whether the engine ever hears
 * about a release. Pushing the hide itself into {@code HiddenPlayersManager} needs a live world, so
 * with no sessions registered {@code runOnWorld} is a no-op here and only the accounting runs — which
 * is exactly the layer the stale-hide bug lived in.
 */
final class VisibilityServiceTest {
    private final MysticQuestsEventBus eventBus = new MysticQuestsEventBus(null);
    private final List<StateEvents.VisibilityChange> changes = new ArrayList<>();
    private final VisibilityService visibility =
            new VisibilityService(new PlayerSessionService(null), eventBus, null, null);

    private final UUID viewer = UUID.randomUUID();
    private final UUID other = UUID.randomUUID();
    private final UUID subject = UUID.randomUUID();

    VisibilityServiceTest() {
        eventBus.subscribe(StateEvents.VisibilityChange.class, changes::add);
    }

    @Test
    void hidingAndShowingIsReportedOnce() {
        assertTrue(visibility.hide(viewer, subject));
        assertTrue(visibility.isHidden(viewer, subject));
        assertFalse(visibility.isEmpty());

        // A second hide from the same source is not a new state change.
        assertFalse(visibility.hide(viewer, subject));

        assertTrue(visibility.show(viewer, subject));
        assertFalse(visibility.isHidden(viewer, subject));
        assertTrue(visibility.isEmpty());
        assertEquals(List.of(true, false), hiddenFlags());
    }

    @Test
    void aRuleReleaseDoesNotCancelAnEventHide() {
        visibility.hide(viewer, subject, VisibilityService.Source.EVENT);
        visibility.hide(viewer, subject, VisibilityService.Source.RULE);

        assertFalse(visibility.show(viewer, subject, VisibilityService.Source.RULE));
        assertTrue(visibility.isHidden(viewer, subject));
        assertTrue(visibility.isHiddenBy(viewer, subject, VisibilityService.Source.EVENT));

        assertTrue(visibility.show(viewer, subject, VisibilityService.Source.EVENT));
        assertFalse(visibility.isHidden(viewer, subject));
    }

    /**
     * The regression this exists for: a player who disconnects while hidden used to be dropped from
     * every viewer's map without anything being released, so their UUID stayed in each viewer's
     * engine-side hidden set with no record left to remove it. They came back permanently invisible.
     */
    @Test
    void aDepartingSubjectIsReleasedFromEveryViewer() {
        visibility.hide(viewer, subject);
        visibility.hide(other, subject);
        changes.clear();

        visibility.clearPlayer(subject);

        assertFalse(visibility.isHidden(viewer, subject));
        assertFalse(visibility.isHidden(other, subject));
        assertTrue(visibility.isEmpty(), "every hide of the departing player must be accounted for");
        assertEquals(2, changes.size(), "each viewer must be told the subject is visible again");
        assertEquals(List.of(false, false), hiddenFlags());
    }

    @Test
    void aDepartingViewerReleasesWhatTheyCouldNotSee() {
        visibility.hide(viewer, subject);
        visibility.hide(viewer, other);
        changes.clear();

        visibility.clearPlayer(viewer);

        assertTrue(visibility.hiddenFrom(viewer).isEmpty());
        assertTrue(visibility.isEmpty());
        assertEquals(2, changes.size());
    }

    @Test
    void clearAllDropsEveryOverride() {
        visibility.hide(viewer, subject);
        visibility.hide(other, subject);
        visibility.hide(viewer, other);

        visibility.clearAll();

        assertTrue(visibility.isEmpty());
        assertTrue(visibility.hiddenFrom(viewer).isEmpty());
        assertTrue(visibility.hiddenFrom(other).isEmpty());
    }

    @Test
    void aPlayerIsNeverHiddenFromThemselves() {
        assertFalse(visibility.hide(viewer, viewer));
        assertTrue(visibility.isEmpty());
    }

    private List<Boolean> hiddenFlags() {
        return changes.stream().map(StateEvents.VisibilityChange::hidden).toList();
    }
}
