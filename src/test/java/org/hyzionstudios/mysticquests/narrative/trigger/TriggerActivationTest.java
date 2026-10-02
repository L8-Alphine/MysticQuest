package org.hyzionstudios.mysticquests.narrative.trigger;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §5.2 and §26.3 "Per-session trigger disable". */
final class TriggerActivationTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final String GATE = "avalon:temple_gate";

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private TriggerActivationService activation() {
        return kit.runtime().activation();
    }

    @Test
    void disablingForOnePlayerDoesNotDisableItForAnother() {
        activation().set(TriggerScope.PLAYER, GATE, false, ScopeContext.player(ALICE));
        assertFalse(activation().isEnabled(GATE, ALICE));
        assertTrue(activation().isEnabled(GATE, BOB));
        assertTrue(activation().isEnabled("avalon:other", ALICE), "other volumes are untouched");
    }

    @Test
    void sessionOverridesAreScopedToTheSessionsParticipants() {
        QuestSession alices = kit.runtime().sessions().open(SessionOwner.player(ALICE), "druid_temple", "1");
        activation().set(TriggerScope.STORY_SESSION, GATE, false,
                ScopeContext.player(ALICE).withSession(alices.id(), alices.storyKey()));
        assertFalse(activation().isEnabled(GATE, ALICE));
        assertTrue(activation().isEnabled(GATE, BOB));

        kit.parties.put(ALICE, "p1");
        kit.parties.put(BOB, "p1");
        QuestSession party = kit.runtime().sessions().open(SessionOwner.party("p1"), "fire_trial", "1");
        activation().set(TriggerScope.STORY_SESSION, "avalon:brazier", false,
                ScopeContext.player(BOB).withSession(party.id(), party.storyKey()));
        assertFalse(activation().isEnabled("avalon:brazier", ALICE), "a party session applies to every member");
        assertFalse(activation().isEnabled("avalon:brazier", BOB));
    }

    @Test
    void theMostSpecificLevelDecides() {
        activation().set(TriggerScope.GLOBAL, GATE, false, ScopeContext.none());
        assertFalse(activation().isEnabled(GATE, ALICE));
        assertFalse(activation().isEnabled(GATE, null), "non-players see only the global level");

        activation().set(TriggerScope.PLAYER, GATE, true, ScopeContext.player(ALICE));
        assertTrue(activation().isEnabled(GATE, ALICE), "player beats global");
        assertFalse(activation().isEnabled(GATE, BOB));
        assertEquals(TriggerScope.PLAYER, activation().decide(GATE, ALICE).decidedBy());

        activation().set(TriggerScope.PLAYER, GATE, null, ScopeContext.player(ALICE));
        assertEquals(TriggerScope.GLOBAL, activation().decide(GATE, ALICE).decidedBy(), "clearing falls back");
        activation().set(TriggerScope.GLOBAL, GATE, null, ScopeContext.none());
        assertNull(activation().decide(GATE, ALICE).decidedBy());
        assertTrue(activation().isEnabled(GATE, ALICE), "enabled by default");
    }

    @Test
    void whenTwoOfAPlayersSessionsDisagreeDisabledWins() {
        QuestSession first = kit.runtime().sessions().open(SessionOwner.player(ALICE), "a", "1");
        QuestSession second = kit.runtime().sessions().open(SessionOwner.player(ALICE), "b", "1");
        activation().set(TriggerScope.STORY_SESSION, GATE, true, ScopeContext.player(ALICE).withSession(first.id(), "a"));
        activation().set(TriggerScope.STORY_SESSION, GATE, false, ScopeContext.player(ALICE).withSession(second.id(), "b"));
        assertFalse(activation().isEnabled(GATE, ALICE));
    }

    @Test
    void overridesSurviveARestart() {
        activation().set(TriggerScope.PLAYER, GATE, false, ScopeContext.player(ALICE));
        kit.runtime().flush();
        kit.restart();
        assertFalse(kit.runtime().activation().isEnabled(GATE, ALICE));
    }

    @Test
    void scopesWithoutAnOwnerAreRefused() {
        assertEquals(DiagnosticCode.SCOPE_UNRESOLVED,
                activation().set(TriggerScope.PARTY, GATE, false, ScopeContext.player(ALICE)).code());
        assertEquals(DiagnosticCode.SCOPE_UNRESOLVED,
                activation().set(TriggerScope.STORY_SESSION, GATE, false, ScopeContext.player(ALICE)).code());
    }

    @Test
    void routingDropsEventsForAudiencesThatDisabledTheVolume() {
        activation().set(TriggerScope.PLAYER, GATE, false, ScopeContext.player(ALICE));
        assertFalse(kit.runtime().triggers().handle(new TriggerEvent(GATE, "ENTER", ALICE, ALICE, List.of()), "avalon").enabled());
        assertTrue(kit.runtime().triggers().handle(new TriggerEvent(GATE, "ENTER", BOB, BOB, List.of()), "avalon").enabled());
    }
}
