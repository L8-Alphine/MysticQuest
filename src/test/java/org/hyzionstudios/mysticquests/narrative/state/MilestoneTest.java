package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime.Milestone;
import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Duration;
import java.util.List;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §19.1 "Important story milestones": player-scoped tags marked as milestones, read for the web portal. */
final class MilestoneTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void milestonesAreReadNewestFirstWithoutLoadingTheOfflinePlayer() throws IOException {
        kit.load("""
                { "tagSchemas": [
                    { "id": "grove:met_warden", "scope": "player", "milestone": "Met the Grove Warden" },
                    { "id": "grove:seal_broken", "scope": "player", "milestone": "Broke the grove seal" },
                    { "id": "grove:ordinary", "scope": "player" } ] }
                """);
        ScopeContext alice = ScopeContext.player(ALICE);
        kit.runtime().tags().add(alice, null, id("grove:met_warden"), null, "test");
        kit.clock.advance(Duration.ofMinutes(5));
        kit.runtime().tags().add(alice, null, id("grove:seal_broken"), null, "test");
        kit.runtime().tags().add(alice, null, id("grove:ordinary"), null, "test");
        kit.runtime().flush();
        kit.restart();

        List<Milestone> milestones = kit.runtime().milestones(ALICE);
        assertEquals(List.of("Broke the grove seal", "Met the Grove Warden"), milestones.stream().map(Milestone::text).toList());
        assertTrue(kit.runtime().store().loaded(ScopeOwner.player(ALICE)).isEmpty(),
                "a portal read leaves the offline player unloaded");
    }

    @Test
    void aMilestoneMustBePlayerScoped() {
        DiagnosticReport report = kit.compile("""
                { "tagSchemas": [ { "id": "grove:seal_broken", "scope": "quest_session", "milestone": "Broke the seal" } ] }
                """);
        assertTrue(report.has(DiagnosticCode.INVALID_SCOPE), report.format());
    }
}
