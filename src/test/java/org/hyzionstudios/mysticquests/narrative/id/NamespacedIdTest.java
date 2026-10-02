package org.hyzionstudios.mysticquests.narrative.id;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class NamespacedIdTest {
    @Test
    void parsesTheSpecificationExamples() {
        for (String raw : new String[] {
                "hyzion:avalon.discovered", "hyzion:druid_temple.entered", "hyzion:old_man.met",
                "mysticquests:tutorial.complete", "my-mod:a/b-c.d"}) {
            assertEquals(raw, NamespacedId.parse(raw).toString());
        }
    }

    @Test
    void refusesWithAMessageThatSaysHowToFixIt() {
        IllegalArgumentException missing = assertThrows(IllegalArgumentException.class, () -> NamespacedId.parse("completed"));
        assertTrue(missing.getMessage().contains("yournamespace:completed"), missing.getMessage());

        assertNotNull(NamespacedId.describeProblem("Hyzion:x"), "upper case is ambiguous across filesystems");
        assertNotNull(NamespacedId.describeProblem("hyzion:a..b"), "empty path segments are refused");
        assertNotNull(NamespacedId.describeProblem("hyzion:a:b"), "only one separator");
        assertNotNull(NamespacedId.describeProblem("hyzion:"), "a namespace alone is not an id");
        assertNotNull(NamespacedId.describeProblem("hyzion:" + "x".repeat(NamespacedId.MAX_LENGTH)));
        assertNull(NamespacedId.describeProblem("hyzion:ok"));
    }

    /** v1 tags were any string; migration folds them into a valid path rather than dropping them. */
    @Test
    void legacyNamesFoldIntoTheDefaultNamespace() {
        assertEquals("legacy:met_elder", NamespacedId.fromLegacy("met_elder", "legacy").toString());
        assertEquals("legacy:boss_killed_", NamespacedId.fromLegacy("Boss Killed!", "legacy").toString());
        assertEquals("legacy:a_b", NamespacedId.fromLegacy("a..b", "legacy").toString());
        assertEquals("legacy:_", NamespacedId.fromLegacy("...", "legacy").toString());
        assertEquals("hyzion:already.namespaced", NamespacedId.fromLegacy("hyzion:already.namespaced", "legacy").toString());
        String longName = "x".repeat(400);
        assertTrue(NamespacedId.fromLegacy(longName, "legacy").toString().length() <= NamespacedId.MAX_LENGTH);
    }
}
