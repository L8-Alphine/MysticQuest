package org.hyzionstudios.mysticquests.api;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MysticQuestsRegistryTest {
    private final MysticQuestsRegistry registry = new MysticQuestsRegistry(null);

    @Test
    void registersAndResolvesAnExtensionType() {
        QuestEventHandler handler = context -> { };
        assertTrue(registry.registerEvent("mymod:grant_skill", handler));
        assertSame(handler, registry.event("mymod:grant_skill"));
        assertTrue(registry.eventTypes().contains("mymod:grant_skill"));
    }

    /** Shadowing a built-in would make content mean different things depending on mod load order. */
    @Test
    void refusesToShadowBuiltInTypes() {
        assertFalse(registry.registerEvent("tag", context -> { }));
        assertFalse(registry.registerEvent("giveItem", context -> { }));
        assertFalse(registry.registerEvent("hidePlayer", context -> { }));
        assertFalse(registry.registerCondition("variable", context -> true));
        assertFalse(registry.registerCondition("nearEntity", context -> true));

        assertNull(registry.event("tag"));
        assertNull(registry.condition("variable"));
    }

    /** First registration wins, so a second mod cannot silently replace the first one's behaviour. */
    @Test
    void firstRegistrationWins() {
        QuestConditionHandler first = context -> true;
        assertTrue(registry.registerCondition("mymod:check", first));
        assertFalse(registry.registerCondition("mymod:check", context -> false));
        assertSame(first, registry.condition("mymod:check"));
    }

    @Test
    void refusesBlankNamesAndNullHandlers() {
        assertFalse(registry.registerEvent("", context -> { }));
        assertFalse(registry.registerEvent("   ", context -> { }));
        assertFalse(registry.registerEvent("mymod:thing", null));
        assertTrue(registry.eventTypes().isEmpty());
    }

    @Test
    void unregisterRemovesTheType() {
        registry.registerEvent("mymod:temp", context -> { });
        assertNotNull(registry.event("mymod:temp"));

        assertTrue(registry.unregisterEvent("mymod:temp"));
        assertNull(registry.event("mymod:temp"));
        assertFalse(registry.unregisterEvent("mymod:temp"));
    }

    /** Names are trimmed on the way in so trailing whitespace in a config cannot orphan a handler. */
    @Test
    void namesAreTrimmed() {
        registry.registerEvent("  mymod:spaced  ", context -> { });
        assertNotNull(registry.event("mymod:spaced"));
    }
}
