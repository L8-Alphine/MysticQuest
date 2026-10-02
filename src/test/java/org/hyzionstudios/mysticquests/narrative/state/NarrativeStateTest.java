package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import com.fasterxml.jackson.databind.node.TextNode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.1 and §4.2: namespaced tags with expiry and provenance, typed scoped variables. */
final class NarrativeStateTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private NarrativeTestKit kit;

    @BeforeEach
    void setUp() {
        kit = new NarrativeTestKit();
        kit.load("""
                {
                  "tagSchemas": [
                    { "id": "hyzion:avalon.discovered", "scope": "player" },
                    { "id": "hyzion:buff.blessed", "scope": "player", "ttl": "10m" },
                    { "id": "hyzion:party.ready", "scope": "party" }
                  ],
                  "variableSchemas": [
                    { "id": "hyzion:keys_found", "type": "integer", "scope": "player", "default": 0 },
                    { "id": "hyzion:phase", "type": "enum", "values": ["idle", "awake"], "scope": "server" }
                  ]
                }
                """);
    }

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private QuestTagService tags() {
        return kit.runtime().tags();
    }

    private QuestVariableService variables() {
        return kit.runtime().variables();
    }

    @Test
    void tagsAreScopedPerOwnerAndCarryProvenance() {
        ScopeContext alice = ScopeContext.player(ALICE);
        assertTrue(tags().add(alice, null, id("hyzion:avalon.discovered"), null, "puzzle:x").changed());
        assertTrue(tags().exists(alice, null, id("hyzion:avalon.discovered")));
        assertFalse(tags().exists(ScopeContext.player(BOB), null, id("hyzion:avalon.discovered")), "Bob's story is his own");
        TagRecord record = tags().find(alice, null, id("hyzion:avalon.discovered")).orElseThrow();
        assertEquals("puzzle:x", record.source());
        assertEquals(kit.clock.instant(), record.addedAt());

        assertFalse(tags().add(alice, null, id("hyzion:avalon.discovered"), null, "again").changed(),
                "re-adding a live tag is not a change");
        assertTrue(tags().toggle(alice, null, id("hyzion:avalon.discovered"), "toggle").changed());
        assertFalse(tags().exists(alice, null, id("hyzion:avalon.discovered")));
    }

    @Test
    void tagsExpireWithoutASweep() {
        ScopeContext alice = ScopeContext.player(ALICE);
        tags().add(alice, null, id("hyzion:buff.blessed"), null, "shrine");
        kit.clock.advance(Duration.ofMinutes(9));
        assertTrue(tags().exists(alice, null, id("hyzion:buff.blessed")));
        kit.clock.advance(Duration.ofMinutes(1));
        assertFalse(tags().exists(alice, null, id("hyzion:buff.blessed")), "the declared ten-minute ttl has passed");

        tags().add(alice, null, id("hyzion:buff.blessed"), Duration.ofSeconds(30), "short");
        kit.clock.advance(Duration.ofSeconds(31));
        assertFalse(tags().exists(alice, null, id("hyzion:buff.blessed")), "an explicit ttl overrides the default");
    }

    @Test
    void unknownTagsAndWrongScopesAreRefusedNotRedirected() {
        ScopeContext alice = ScopeContext.player(ALICE);
        assertEquals(DiagnosticCode.UNKNOWN_TAG, tags().add(alice, null, id("hyzion:typo.tag"), null, "").code());
        assertEquals(DiagnosticCode.INVALID_SCOPE,
                tags().add(alice, VariableScope.SERVER, id("hyzion:avalon.discovered"), null, "").code());
        assertEquals(DiagnosticCode.SCOPE_UNRESOLVED, tags().add(alice, null, id("hyzion:party.ready"), null, "").code(),
                "a solo player has no party to write to");

        kit.parties.put(ALICE, "party-1");
        ScopeContext inParty = alice.withParty("party-1");
        assertTrue(tags().add(inParty, null, id("hyzion:party.ready"), null, "").changed());
        assertTrue(tags().exists(ScopeContext.player(BOB).withParty("party-1"), null, id("hyzion:party.ready")),
                "party state is shared by the party");
    }

    @Test
    void undeclaredIdsAreAcceptedOnlyInTheLegacyNamespace() {
        ScopeContext alice = ScopeContext.player(ALICE);
        assertTrue(tags().add(alice, null, id("legacy:met_elder"), null, "migration:v1").changed());
        assertTrue(variables().setRaw(alice, null, id("legacy:reputation"), TextNode.valueOf("12")).changed());
        assertEquals(new QuestValue.StringValue("12"), variables().get(alice, null, id("legacy:reputation")).orElseThrow());
    }

    @Test
    void variablesAreTypedAndApplyTheirDefault() {
        ScopeContext alice = ScopeContext.player(ALICE);
        assertEquals(new IntValue(0), variables().get(alice, null, id("hyzion:keys_found")).orElseThrow());
        assertEquals(DiagnosticCode.INVALID_VARIABLE_TYPE,
                variables().setRaw(alice, null, id("hyzion:keys_found"), TextNode.valueOf("four")).code());
        assertEquals(DiagnosticCode.INVALID_VARIABLE_TYPE,
                variables().set(alice, null, id("hyzion:keys_found"), new QuestValue.StringValue("4")).code());
        assertEquals(new IntValue(3),
                variables().increment(alice, null, id("hyzion:keys_found"), new QuestValue.LongValue(3)).value());
        assertEquals(DiagnosticCode.INVALID_VARIABLE_TYPE,
                variables().setRaw(ScopeContext.none(), null, id("hyzion:phase"), TextNode.valueOf("asleep")).code());
        assertTrue(variables().setRaw(ScopeContext.none(), null, id("hyzion:phase"), TextNode.valueOf("awake")).changed());
        assertEquals(DiagnosticCode.UNKNOWN_VARIABLE,
                variables().setRaw(alice, null, id("hyzion:nope"), TextNode.valueOf("1")).code());
    }

    @Test
    void incrementsRefuseToOverflow() {
        ScopeContext alice = ScopeContext.player(ALICE);
        variables().set(alice, null, id("hyzion:keys_found"), new IntValue(Integer.MAX_VALUE));
        StateResult result = variables().increment(alice, null, id("hyzion:keys_found"), new QuestValue.LongValue(1));
        assertTrue(result.rejected());
        assertEquals(new IntValue(Integer.MAX_VALUE), variables().get(alice, null, id("hyzion:keys_found")).orElseThrow(),
                "a refused increment leaves the value untouched");
    }

    @Test
    void unsupportedScopesFailWhereTheyAreUsed() {
        ScopeContext alice = ScopeContext.player(ALICE);
        assertEquals(DiagnosticCode.UNSUPPORTED_SCOPE, tags().add(alice, VariableScope.ACCOUNT, id("legacy:any"), null, "").code(),
                "account scope has no identity provider in a standalone deployment");
        assertTrue(kit.compile("""
                { "variableSchemas": [ { "id": "hyzion:acct", "type": "integer", "scope": "account" } ] }
                """).has(DiagnosticCode.UNSUPPORTED_SCOPE), "and declaring one fails the reload");
    }

    /** §26.2: content version changes while a player has persisted state. */
    @Test
    void storedValuesSurviveATypeChangeAcrossReleases() {
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:score", "type": "string", "scope": "player" } ] }
                """);
        ScopeContext alice = ScopeContext.player(ALICE);
        variables().setRaw(alice, null, id("hyzion:score"), TextNode.valueOf("42"));
        kit.runtime().flush();
        kit.restart();
        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:score", "type": "integer", "scope": "player", "default": -1 } ] }
                """);
        assertEquals(new IntValue(42), kit.runtime().variables().get(alice, null, id("hyzion:score")).orElseThrow(),
                "the old text value still reads under the new type");

        kit.load("""
                { "variableSchemas": [ { "id": "hyzion:score", "type": "boolean", "scope": "player" } ] }
                """);
        assertTrue(kit.runtime().variables().get(alice, null, id("hyzion:score")).isEmpty(),
                "a value that cannot be read under the new type falls back to the default");
        assertTrue(kit.problems.stream().anyMatch(problem -> problem.contains("MIGRATION")), kit.problems.toString());
    }

    @Test
    void stateSurvivesARestartAndTemporaryStateDoesNot() {
        ScopeContext alice = ScopeContext.player(ALICE);
        tags().add(alice, null, id("hyzion:avalon.discovered"), null, "x");
        variables().set(alice, null, id("hyzion:keys_found"), new IntValue(2));
        tags().add(alice, VariableScope.TEMPORARY, id("legacy:scene.only"), null, "x");
        kit.runtime().flush();

        kit.restart();
        assertTrue(kit.runtime().tags().exists(alice, null, id("hyzion:avalon.discovered")));
        assertEquals(new IntValue(2), kit.runtime().variables().get(alice, null, id("hyzion:keys_found")).orElseThrow());
        assertFalse(kit.runtime().tags().exists(alice, VariableScope.TEMPORARY, id("legacy:scene.only")));
    }
}
