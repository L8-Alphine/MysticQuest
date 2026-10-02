package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §6 and §26.3 "Staff bypass": staff stay able to observe everyone, without changing the player's
 * quest or the hide policy for normal viewers. Engine calls need a live world, so with no sessions
 * registered only the policy and presentation layers run here, as in {@link VisibilityServiceTest}.
 */
final class VisibilityBypassTest {
    private final MysticQuestsEventBus eventBus = new MysticQuestsEventBus(null);
    private final List<Object> events = new ArrayList<>();
    private final VisibilityService visibility =
            new VisibilityService(new PlayerSessionService(null), eventBus, null, null);

    private final UUID staff = UUID.randomUUID();
    private final UUID player = UUID.randomUUID();
    private final UUID hiddenOne = UUID.randomUUID();

    VisibilityBypassTest() {
        eventBus.subscribe(StateEvents.VisibilityChange.class, events::add);
        eventBus.subscribe(StateEvents.VisibilityBypassChange.class, events::add);
    }

    @Test
    void bypassChangesWhatStaffSeeButNotTheStorysPolicy() {
        visibility.hide(staff, hiddenOne);
        visibility.hide(player, hiddenOne, VisibilityService.Source.RULE);
        events.clear();

        assertTrue(visibility.setBypass(staff, true));

        assertTrue(visibility.isHidden(staff, hiddenOne), "quest conditions still see the hide");
        assertEquals(Set.of(hiddenOne), visibility.hiddenFrom(staff), "and so does the API");
        assertFalse(visibility.isPresentedHidden(staff, hiddenOne), "but staff are shown the subject");
        assertTrue(visibility.presentedHiddenFrom(staff).isEmpty());
        assertTrue(visibility.isPresentedHidden(player, hiddenOne), "normal viewers are unaffected");
        assertEquals(List.of(new StateEvents.VisibilityBypassChange(staff, true)), events,
                "no VisibilityChange: nothing about the story changed");

        assertTrue(visibility.setBypass(staff, false));
        assertTrue(visibility.isPresentedHidden(staff, hiddenOne), "switching off restores the hide at once");
    }

    @Test
    void hidesAddedDuringBypassAreRecordedAndApplyAfterIt() {
        visibility.setBypass(staff, true);
        assertTrue(visibility.hide(staff, hiddenOne), "the story still records the hide");
        assertFalse(visibility.isPresentedHidden(staff, hiddenOne));
        visibility.setBypass(staff, false);
        assertTrue(visibility.isPresentedHidden(staff, hiddenOne));
    }

    @Test
    void theAlwaysPermissionCannotBeSwitchedOffAndLosingItLeavesBypassAsIs() {
        visibility.setBypassForced(staff, true);
        assertTrue(visibility.isBypassing(staff));
        assertFalse(visibility.setBypass(staff, false), "held on by permission");
        assertTrue(visibility.isBypassing(staff));

        visibility.setBypassForced(staff, false);
        assertTrue(visibility.isBypassing(staff), "losing the permission does not snap players out of view");
        assertTrue(visibility.setBypass(staff, false));
    }

    @Test
    void bypassEndsWithTheSessionAndSurvivesAContentReload() {
        visibility.setBypass(staff, true);
        visibility.clearAll();
        assertTrue(visibility.isBypassing(staff), "a reload drops story hides, not staff choices");
        visibility.forgetBypass(staff);
        assertFalse(visibility.isBypassing(staff));
    }

    /**
     * §7 and §26.3 "NameTag layering": what nameplate mods consult. Releasing the quest hide changes
     * only MysticQuests' answer; any other system's hide is asked separately and is not touched.
     */
    @Test
    void canSeeIsThePresentationAnswerNameplateModsConsult() {
        assertTrue(visibility.canSee(player, hiddenOne));
        visibility.hide(player, hiddenOne, VisibilityService.Source.RULE);
        assertFalse(visibility.canSee(player, hiddenOne));
        assertTrue(visibility.canSee(hiddenOne, player), "per viewer: the other direction is unaffected");
        assertTrue(visibility.canSee(player, player), "everyone sees their own nameplate");

        visibility.setBypass(player, true);
        assertTrue(visibility.canSee(player, hiddenOne), "staff in bypass are shown it");
        visibility.setBypass(player, false);

        visibility.show(player, hiddenOne, VisibilityService.Source.RULE);
        assertTrue(visibility.canSee(player, hiddenOne), "quest completion lifts only the quest's reason");
    }

    @Test
    void reasonsListEveryLayerMysticQuestsHolds() {
        visibility.hide(player, hiddenOne, VisibilityService.Source.EVENT);
        visibility.hide(player, hiddenOne, VisibilityService.Source.RULE);
        assertEquals(List.of("EVENT", "RULE"), visibility.reasons(player, hiddenOne));
        assertTrue(visibility.reasons(player, staff).isEmpty());
    }
}
