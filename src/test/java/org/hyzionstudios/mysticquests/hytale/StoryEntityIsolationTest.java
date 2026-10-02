package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.narrative.persistence.InMemoryDocumentStore;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import org.junit.jupiter.api.Test;

import java.time.Clock;
import java.util.Optional;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * §26.2 "quest entity cannot target unrelated player" and §26.3 "Story boss": the hit veto decision
 * the damage filter applies, either way round. Attacker and victim each arrive as a player UUID when
 * they are a player and an entity UUID always, as the engine supplies them.
 */
final class StoryEntityIsolationTest {
    private static final UUID OWNER = UUID.randomUUID();
    private static final UUID STRANGER = UUID.randomUUID();
    private static final UUID BOSS = UUID.randomUUID();
    private static final UUID WOLF = UUID.randomUUID();

    private final StoryEntityRegistry registry =
            new StoryEntityRegistry(new InMemoryDocumentStore(), Clock.systemUTC(), player -> Optional.empty(), problem -> { });

    StoryEntityIsolationTest() {
        registry.claim(new EntityRefValue("uuid", BOSS.toString()), "qs-1", SessionOwner.player(OWNER), "story");
    }

    @Test
    void theBossAndItsOwnerFightNormally() {
        assertFalse(StoryEntitySystems.blocked(registry, null, BOSS, OWNER, UUID.randomUUID()));
        assertFalse(StoryEntitySystems.blocked(registry, OWNER, UUID.randomUUID(), null, BOSS));
    }

    @Test
    void anUnrelatedPlayerCannotHitOrBeHitByTheBoss() {
        assertTrue(StoryEntitySystems.blocked(registry, null, BOSS, STRANGER, UUID.randomUUID()), "boss to stranger");
        assertTrue(StoryEntitySystems.blocked(registry, STRANGER, UUID.randomUUID(), null, BOSS), "stranger to boss");
    }

    @Test
    void everythingElseIsUntouched() {
        assertFalse(StoryEntitySystems.blocked(registry, null, WOLF, STRANGER, UUID.randomUUID()), "ordinary NPC to player");
        assertFalse(StoryEntitySystems.blocked(registry, STRANGER, UUID.randomUUID(), OWNER, UUID.randomUUID()), "player to player");
        assertFalse(StoryEntitySystems.blocked(registry, null, BOSS, null, WOLF), "story entity to ordinary NPC");
    }
}
