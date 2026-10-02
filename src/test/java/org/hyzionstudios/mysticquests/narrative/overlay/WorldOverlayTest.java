package org.hyzionstudios.mysticquests.narrative.overlay;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.Outcome;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.VisibilityService;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.Set;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §9 and §26.2 "Fake/world overlay capability absent vs supported": per-audience presence of
 * overlay entities. A hitbox entity's presence is also its collision, so these are the
 * "who can walk through this door" tests.
 */
final class WorldOverlayTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final UUID SEAL = UUID.fromString("00000000-0000-4000-8000-0000000005ea");
    private static final UUID BRIDGE = UUID.fromString("00000000-0000-4000-8000-00000000b1d6");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    private void loadTemple() {
        kit.load("""
                {
                  "overlays": [
                    { "id": "hyzion:druid_temple.seal",   "entity": "uuid:%s" },
                    { "id": "hyzion:druid_temple.bridge", "entity": "uuid:%s", "default": "absent" }
                  ],
                  "puzzles": [ {
                    "id": "hyzion:druid_temple.lever", "story": "hyzion:druid_temple", "audience": "auto",
                    "inputs": ["lever"], "rule": "any",
                    "outputs": [ { "type": "mysticquests:overlay.hide", "overlay": "hyzion:druid_temple.seal" } ]
                  } ]
                }
                """.formatted(SEAL, BRIDGE));
    }

    private WorldOverlayRegistry overlays() {
        return kit.runtime().overlays();
    }

    @Test
    void solvingThePuzzleOpensTheSealForTheSolverOnly() {
        loadTemple();
        assertFalse(overlays().hides(ALICE, SEAL), "the seal blocks everyone by default");
        assertFalse(overlays().hides(BOB, SEAL));

        assertEquals(Outcome.COMPLETED, kit.runtime().puzzles().input(ALICE, null, id("hyzion:druid_temple.lever"), "lever", true).outcome());

        assertTrue(overlays().hides(ALICE, SEAL), "Alice is no longer sent the seal, so she walks through it");
        assertFalse(overlays().hides(BOB, SEAL), "Bob still collides with it");
        kit.runtime().flush();
        kit.restart();
        assertTrue(kit.runtime().overlays().hides(ALICE, SEAL), "the opened seal stays open across a restart");
    }

    @Test
    void anAbsentBridgeAppearsOnlyForThePartyThatEarnedIt() {
        loadTemple();
        assertEquals(Set.of(BRIDGE), overlays().hiddenFrom(ALICE), "absent by default for everyone");
        kit.parties.put(ALICE, "p1");
        kit.parties.put(BOB, "p1");
        kit.runtime().activation().set(TriggerScope.PARTY, WorldOverlayRegistry.key(id("hyzion:druid_temple.bridge")), true,
                ScopeContext.player(ALICE).withParty("p1"));
        assertFalse(overlays().hides(ALICE, BRIDGE));
        assertFalse(overlays().hides(BOB, BRIDGE));
        assertTrue(overlays().hides(UUID.randomUUID(), BRIDGE), "strangers cannot use it");
    }

    @Test
    void theMostSpecificLevelDecidesAsForTriggers() {
        loadTemple();
        String seal = WorldOverlayRegistry.key(id("hyzion:druid_temple.seal"));
        kit.runtime().activation().set(TriggerScope.GLOBAL, seal, false, ScopeContext.none());
        assertTrue(overlays().hides(BOB, SEAL), "opened for everyone");
        kit.runtime().activation().set(TriggerScope.PLAYER, seal, true, ScopeContext.player(BOB));
        assertFalse(overlays().hides(BOB, SEAL), "but closed again for Bob alone");
        assertTrue(overlays().hides(ALICE, SEAL));
    }

    @Test
    void generationOverlaysFollowTheirNpcAndCombineWithOtherLayers() {
        UUID stable = UUID.randomUUID();
        UUID body = UUID.randomUUID();
        kit.load("""
                { "overlays": [ { "id": "hyzion:rubble", "entity": "generation:%s", "default": "absent" } ] }
                """.formatted(stable));
        assertTrue(overlays().wantsObservation());
        assertTrue(overlays().isEmpty(), "nothing to hide until the entity is seen");
        overlays().observe(body, stable);
        assertTrue(overlays().hides(ALICE, body));

        VisibilityService visibility = new VisibilityService(new PlayerSessionService(null), new MysticQuestsEventBus(null), null, null);
        visibility.addPresentationLayer(kit.runtime().storyEntities());
        visibility.addPresentationLayer(overlays());
        assertFalse(visibility.isEmpty());
        assertEquals(Set.of(body), visibility.presentedHiddenFrom(ALICE));
        visibility.setBypass(ALICE, true);
        assertTrue(visibility.presentedHiddenFrom(ALICE).isEmpty(), "staff in bypass see every overlay");
    }

    @Test
    void unsupportedOrBrokenOverlaysFailTheReload() {
        DiagnosticReport block = kit.compile("""
                { "overlays": [ { "id": "hyzion:door", "kind": "block", "entity": "uuid:%s" } ] }
                """.formatted(SEAL));
        assertTrue(block.has(DiagnosticCode.INVALID_PARAMETER), "per-player blocks are refused with the entity pattern as the fix");

        DiagnosticReport broken = kit.compile("""
                { "overlays": [
                    { "id": "hyzion:a", "entity": "npc:guard" },
                    { "id": "hyzion:b", "entity": "uuid:not-a-uuid" },
                    { "id": "hyzion:c", "entity": "uuid:%s", "default": "sometimes" } ],
                  "puzzles": [ { "id": "hyzion:p", "inputs": ["x"], "rule": "any",
                    "outputs": [ { "type": "mysticquests:overlay.hide", "overlay": "hyzion:missing" } ] } ] }
                """.formatted(SEAL));
        assertTrue(broken.has(DiagnosticCode.UNKNOWN_ENTITY));
        assertTrue(broken.has(DiagnosticCode.INVALID_PARAMETER));
        assertTrue(broken.has(DiagnosticCode.MISSING_REFERENCE), broken.format());
    }
}
