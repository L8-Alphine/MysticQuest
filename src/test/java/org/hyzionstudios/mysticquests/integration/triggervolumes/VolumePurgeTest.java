package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.integration.triggervolumes.VolumePurge.Candidate;
import org.hyzionstudios.mysticquests.integration.triggervolumes.VolumePurge.Plan;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class VolumePurgeTest {
    @Test
    void matchesThePlainNameAndItsNumberedCopiesOnly() {
        assertTrue(VolumePurge.matches("frontier_camp", "frontier_camp"));
        assertTrue(VolumePurge.matches("frontier_camp", "Frontier_Camp (4866)"));
        assertFalse(VolumePurge.matches("frontier_camp", "frontier_camp_2"));
        assertFalse(VolumePurge.matches("frontier_camp", "frontier_camp (x)"));
        assertFalse(VolumePurge.matches("frontier_camp", "old frontier_camp (2)"));
        assertFalse(VolumePurge.matches("frontier_camp", null));
    }

    @Test
    void keepsThePlainNameThenTheLowestCopyAndRemovesTheRest() {
        List<Candidate> volumes = List.of(
                new Candidate("c", "frontier_camp (10)", "1 2 3"),
                new Candidate("b", "frontier_camp (2)", "1 2 3"),
                new Candidate("a", "frontier_camp", "1 2 3"),
                new Candidate("z", "spawn_gate", "9 9 9"));

        Plan one = VolumePurge.plan("frontier_camp", volumes, 1);
        assertEquals(List.of("a"), one.kept().stream().map(Candidate::id).toList());
        assertEquals(List.of("b", "c"), one.removed().stream().map(Candidate::id).toList());
        assertEquals(1, one.positions());

        Plan none = VolumePurge.plan("frontier_camp", volumes, 0);
        assertEquals(3, none.removed().size());
        assertTrue(none.kept().isEmpty());
    }

    @Test
    void identicalNamesKeepTheLowestIdAndCountEveryBlock() {
        List<Candidate> volumes = List.of(
                new Candidate("b2", "frontier_camp", "0 64 0"),
                new Candidate("a1", "frontier_camp", "0 64 0"),
                new Candidate("c3", "frontier_camp", "100 64 0"));

        Plan plan = VolumePurge.plan("frontier_camp", volumes, 1);
        assertEquals("a1", plan.kept().getFirst().id());
        assertEquals(2, plan.removed().size());
        assertEquals(2, plan.positions());
    }

    @Test
    void listsEachVolumeWithItsBlockInCopyOrderUpToTheLimit() {
        List<Candidate> volumes = List.of(
                new Candidate("c", "frontier_camp (3)", "740 144 175"),
                new Candidate("a", "frontier_camp", "676 144 175"),
                new Candidate("b", "frontier_camp (2)", "708 144 175"));
        Plan plan = VolumePurge.plan("frontier_camp", volumes, 1);

        assertEquals(List.of("  frontier_camp at 676 144 175", "  frontier_camp (2) at 708 144 175",
                "  frontier_camp (3) at 740 144 175"), VolumePurge.listing(plan, 20));
        assertEquals(List.of("  frontier_camp at 676 144 175", "  ... and 2 more"),
                VolumePurge.listing(plan, 1));
    }

    @Test
    void keepingMoreThanThereAreRemovesNothing() {
        Plan plan = VolumePurge.plan("frontier_camp",
                List.of(new Candidate("a", "frontier_camp", "0 0 0")), 5);
        assertEquals(1, plan.kept().size());
        assertTrue(plan.removed().isEmpty());
    }
}
