package org.hyzionstudios.mysticquests.narrative.entity;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.TransitionReport;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.VisibilityService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §8 and §26.2/§26.3 "Story boss", "two players, different story entity instances". */
final class StoryEntityTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID CAROL = UUID.fromString("00000000-0000-4000-8000-00000000000c");
    private static final UUID BOSS_A = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID BOSS_B = UUID.fromString("00000000-0000-4000-8000-0000000000b1");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private StoryEntityRegistry registry() {
        return kit.runtime().storyEntities();
    }

    private QuestSession session(SessionOwner owner) {
        return kit.runtime().sessions().open(owner, "druid_temple", "1");
    }

    private void claim(UUID entity, QuestSession session) {
        registry().claim(new EntityRefValue("uuid", entity.toString()), session.id(), session.owner(), session.storyKey());
    }

    @Test
    void twoPlayersInTheSamePlaceEachHaveTheirOwnBoss() {
        claim(BOSS_A, session(SessionOwner.player(ALICE)));
        claim(BOSS_B, session(SessionOwner.player(BOB)));

        assertTrue(registry().allows(ALICE, BOSS_A));
        assertFalse(registry().allows(ALICE, BOSS_B), "Alice cannot see, hit or be hit by Bob's boss");
        assertTrue(registry().allows(BOB, BOSS_B));
        assertFalse(registry().allows(BOB, BOSS_A));
        assertTrue(registry().allows(CAROL, UUID.randomUUID()), "ordinary entities are unrestricted");
        assertEquals(Set.of(BOSS_B), registry().hiddenFrom(ALICE));
        assertEquals(Set.of(BOSS_A, BOSS_B), registry().hiddenFrom(CAROL));
    }

    @Test
    void aPartyBossBelongsToWhoeverIsInThePartyNow() {
        kit.parties.put(ALICE, "p1");
        kit.parties.put(BOB, "p1");
        claim(BOSS_A, session(SessionOwner.party("p1")));
        assertTrue(registry().allows(ALICE, BOSS_A));
        assertTrue(registry().allows(BOB, BOSS_A));
        assertFalse(registry().allows(CAROL, BOSS_A));

        kit.parties.remove(BOB);
        assertFalse(registry().allows(BOB, BOSS_A), "a member who leaves loses the party's boss at once");
        kit.parties.put(CAROL, "p1");
        assertTrue(registry().allows(CAROL, BOSS_A), "and one who joins gains it");
    }

    @Test
    void generationClaimsFollowTheNpcAcrossARepublish() {
        UUID stable = UUID.randomUUID();
        QuestSession alices = session(SessionOwner.player(ALICE));
        registry().claim(new EntityRefValue("generation", stable.toString()), alices.id(), alices.owner(), alices.storyKey());
        UUID firstBody = UUID.randomUUID();
        assertTrue(registry().allows(BOB, firstBody), "not bound until the entity index sees it");

        registry().observe(firstBody, stable);
        assertFalse(registry().allows(BOB, firstBody));

        UUID republished = UUID.randomUUID();
        registry().observe(republished, stable);
        assertFalse(registry().allows(BOB, republished), "the new body carries the same story");
        registry().observe(UUID.randomUUID(), UUID.randomUUID());
        assertEquals(2, registry().hiddenFrom(BOB).size(), "unrelated NPCs are not bound");
    }

    @Test
    void claimsSurviveARestartAndReleaseFreesTheEntity() {
        claim(BOSS_A, session(SessionOwner.player(ALICE)));
        kit.restart();
        assertFalse(registry().allows(BOB, BOSS_A), "still Alice's after a restart, before she rejoins");
        assertTrue(registry().release(new EntityRefValue("uuid", BOSS_A.toString())));
        assertTrue(registry().allows(BOB, BOSS_A));
        kit.restart();
        assertTrue(registry().allows(BOB, BOSS_A), "the release persisted too");
        assertNull(registry().claimOf(BOSS_A));
    }

    @Test
    void contentClaimsAndReleasesThroughAnEntityVariable() {
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:druid_temple.guardian", "type": "entity", "scope": "quest_session" } ] }
                """);
        QuestSession session = session(SessionOwner.player(ALICE));
        ScopeContext scope = ScopeContext.player(ALICE).withSession(session.id(), session.storyKey());
        kit.runtime().variables().set(scope, null, id("hyzion:druid_temple.guardian"), new EntityRefValue("uuid", BOSS_A.toString()));

        DiagnosticReport report = new DiagnosticReport();
        CompileContext context = new CompileContext(kit.runtime().content().schemas(), ScopeSupport.standard(true),
                kit.runtime().conditionTypes(), kit.runtime().actionTypes(), "test");
        var claim = ActionCompiler.compile(parse("""
                [ { "type": "mysticquests:entity.claim", "variable": "hyzion:druid_temple.guardian" } ]
                """), "claim", context, report);
        var release = ActionCompiler.compile(parse("""
                [ { "type": "mysticquests:entity.release", "variable": "hyzion:druid_temple.guardian" } ]
                """), "release", context, report);
        ActionCompiler.compile(parse("""
                [ { "type": "mysticquests:entity.claim" },
                  { "type": "mysticquests:entity.claim", "entity": "npc:guard" } ]
                """), "bad", context, report);
        assertTrue(report.has(DiagnosticCode.INVALID_PARAMETER), "neither entity nor variable");
        assertTrue(report.has(DiagnosticCode.UNKNOWN_ENTITY), "an unresolvable entity kind");

        TransitionReport claimed = kit.runtime().executor().run("t1", claim, new ActionContext(scope, "test"), session);
        assertTrue(claimed.complete());
        assertFalse(registry().allows(BOB, BOSS_A));
        kit.runtime().executor().run("t2", release, new ActionContext(scope, "test"), session);
        assertTrue(registry().allows(BOB, BOSS_A));

        TransitionReport outside = kit.runtime().executor().run("t3", claim, new ActionContext(ScopeContext.player(ALICE), "test"), session);
        assertFalse(outside.complete(), "claiming needs a session to belong to");
    }

    /** Story entities ride the visibility presentation layer, which staff bypass also reveals. */
    @Test
    void storyEntitiesArePresentedOnlyToTheirAudience() {
        VisibilityService visibility = new VisibilityService(new PlayerSessionService(null), new MysticQuestsEventBus(null), null, null);
        visibility.addPresentationLayer(registry());
        assertTrue(visibility.isEmpty());
        claim(BOSS_A, session(SessionOwner.player(ALICE)));

        assertFalse(visibility.isEmpty(), "the per-tick systems must run while a story entity exists");
        assertTrue(visibility.presentedHiddenFrom(ALICE).isEmpty());
        assertEquals(Set.of(BOSS_A), visibility.presentedHiddenFrom(BOB));
        assertTrue(visibility.isPresentedHidden(BOB, BOSS_A));
        assertFalse(visibility.isHidden(BOB, BOSS_A), "not a quest hide: the story's own hide records are untouched");

        visibility.setBypass(BOB, true);
        assertTrue(visibility.presentedHiddenFrom(BOB).isEmpty(), "staff in bypass can observe other audiences' story entities");
    }
}
