package org.hyzionstudios.mysticquests.narrative.entity;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.action.TransitionReport;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import com.fasterxml.jackson.databind.node.ArrayNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.util.Optional;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.parse;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §8 "Loot / rewards: which audience owns the results of this entity?" */
final class StoryEntityDeathTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID BOSS = UUID.fromString("00000000-0000-4000-8000-0000000000a1");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @BeforeEach
    void setUp() {
        kit.load("""
                { "tagSchemas": [ { "id": "grove:guardian_slain", "scope": "quest_session" } ],
                  "variableSchemas": [ { "id": "grove:rewards", "type": "integer", "scope": "player", "default": 0 } ] }
                """);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private QuestSession claimWithOnDeath(SessionOwner owner) {
        QuestSession session = kit.runtime().sessions().open(owner, "grove:sealed_grove", "1");
        ArrayNode onDeath = (ArrayNode) parse("""
                [ { "type": "mysticquests:tag.add", "tag": "grove:guardian_slain" },
                  { "type": "mysticquests:variable.increment", "variable": "grove:rewards" } ]
                """);
        kit.runtime().storyEntities().claim(new EntityRefValue("uuid", BOSS.toString()), session.id(), session.owner(),
                session.storyKey(), onDeath);
        return session;
    }

    private int rewards(UUID player) {
        return kit.runtime().variables().get(ScopeContext.player(player), null, id("grove:rewards"))
                .map(value -> ((IntValue) value).value()).orElse(0);
    }

    private boolean slain(QuestSession session, UUID viewer) {
        return kit.runtime().tags().exists(ScopeContext.player(viewer).withSession(session.id(), session.storyKey()), null,
                id("grove:guardian_slain"));
    }

    @Test
    void theOwningAudienceGetsTheResultsOnceAndTheClaimEnds() {
        QuestSession session = claimWithOnDeath(SessionOwner.player(ALICE));

        Optional<TransitionReport> ran = kit.runtime().onStoryEntityDeath(BOSS, ALICE, "avalon");
        assertTrue(ran.isPresent() && ran.get().complete(), String.valueOf(ran));
        assertTrue(slain(session, ALICE));
        assertEquals(1, rewards(ALICE), "the reward went to the owner");
        assertNull(kit.runtime().storyEntities().claimOf(BOSS), "a dead story entity is released");
        assertTrue(kit.runtime().onStoryEntityDeath(BOSS, ALICE, "avalon").isEmpty(), "a second death report does nothing");
    }

    @Test
    void anOutsiderNeverBecomesTheActorAndClaimsSurviveARestart() {
        QuestSession session = claimWithOnDeath(SessionOwner.player(ALICE));
        kit.runtime().flush();
        kit.restart();

        kit.runtime().onStoryEntityDeath(BOSS, BOB, "avalon");
        assertTrue(slain(session, ALICE), "the onDeath actions were stored with the claim");
        assertEquals(0, rewards(BOB), "Bob, outside the audience, gets nothing");
        assertEquals(1, rewards(ALICE), "the owner is the actor instead");
    }

    @Test
    void ordinaryEntitiesAndBrokenOnDeathListsAreHandled() {
        assertTrue(kit.runtime().onStoryEntityDeath(UUID.randomUUID(), ALICE, "avalon").isEmpty(), "not a story entity");

        DiagnosticReport report = kit.compile("""
                { "puzzles": [ { "id": "grove:p", "inputs": [ { "id": "a", "volume": "w:a" } ], "rule": "all",
                    "outputs": [ { "type": "mysticquests:entity.claim", "entity": "uuid:%s",
                                   "onDeath": [ { "type": "mysticquests:no.such.action" } ] } ] } ] }
                """.formatted(BOSS));
        assertTrue(report.has(DiagnosticCode.UNKNOWN_ACTION), "onDeath is checked at load like any action list: " + report.format());
    }
}
