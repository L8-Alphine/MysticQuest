package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.state.EntityIndexService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Covers the selector forms that do not need a live world. The {@code nearest} and {@code players}
 * forms depend on an entity store and online sessions and are exercised in-game instead.
 */
final class TargetSelectorTest {
    private final MysticStateStore store = new MysticStateStore(new MysticQuestsEventBus(null));
    private final UUID player = UUID.randomUUID();
    private final UUID friend = UUID.randomUUID();

    private final TargetSelector selector = new TargetSelector(
            store,
            new EntityIndexService(),
            new PlayerSessionService(null),
            id -> Set.of(player, friend));

    @Test
    void selfIsTheDefault() {
        assertEquals(List.of(player), selector.resolve((String) null, player, QuestTargetContext.none()));
        assertEquals(List.of(player), selector.resolve("", player, QuestTargetContext.none()));
        assertEquals(List.of(player), selector.resolve("self", player, QuestTargetContext.none()));
    }

    @Test
    void contextResolvesToTheTriggeringEntity() {
        UUID entity = UUID.randomUUID();
        QuestTargetContext context = new QuestTargetContext(
                entity.toString(), "Npc", "Elder", null, null, null, null, null, null);

        assertEquals(List.of(entity), selector.resolve("context", player, context));
    }

    @Test
    void explicitUuidResolvesWithAndWithoutThePrefix() {
        UUID entity = UUID.randomUUID();
        assertEquals(List.of(entity), selector.resolve("uuid:" + entity, player, QuestTargetContext.none()));
        assertEquals(List.of(entity), selector.resolve(entity.toString(), player, QuestTargetContext.none()));
    }

    @Test
    void tagSelectorFindsEveryMatchingEntity() {
        UUID guardOne = UUID.randomUUID();
        UUID guardTwo = UUID.randomUUID();
        UUID merchant = UUID.randomUUID();
        store.addTag(StateKey.of(StateScope.ENTITY, guardOne.toString()), "guard");
        store.addTag(StateKey.of(StateScope.ENTITY, guardTwo.toString()), "guard");
        store.addTag(StateKey.of(StateScope.ENTITY, merchant.toString()), "merchant");

        List<UUID> resolved = selector.resolve("tag:guard", player, QuestTargetContext.none());

        assertEquals(2, resolved.size());
        assertTrue(resolved.containsAll(List.of(guardOne, guardTwo)));
    }

    /**
     * A bare {@code generation} names the NPC the trigger fired against by its stable identity, not
     * by the entity UUID {@code context} yields — the two differ, and only the former survives a
     * republished definition.
     */
    @Test
    void generationSelectorResolvesTheContextNpcStableIdentity() {
        UUID entity = UUID.randomUUID();
        UUID stable = UUID.randomUUID();
        QuestTargetContext context = new QuestTargetContext(
                entity.toString(), "Npc", "Guard", null, null, null, null, null, null)
                .withGeneration("hyzion:avalon_guard", stable.toString());

        assertEquals(List.of(stable), selector.resolve("generation", player, context));
        assertEquals(List.of(entity), selector.resolve("context", player, context));
    }

    /** Without the bridge there is nothing to enumerate, and a definition selector stays empty. */
    @Test
    void generationDefinitionSelectorNeedsTheBridge() {
        QuestTargetContext context = QuestTargetContext.none()
                .withGeneration("hyzion:avalon_guard", UUID.randomUUID().toString());

        assertEquals(List.of(), selector.resolve("generation:hyzion:avalon_guard", player, context));
    }

    @Test
    void partySelectorReturnsEveryMember() {
        List<UUID> resolved = selector.resolve("party", player, QuestTargetContext.none());
        assertEquals(2, resolved.size());
        assertTrue(resolved.containsAll(List.of(player, friend)));
    }

    /** A typo must not quietly fall back to the player and apply an effect to the wrong subject. */
    @Test
    void unresolvableSelectorsYieldNothing() {
        assertEquals(List.of(), selector.resolve("not-a-uuid", player, QuestTargetContext.none()));
        assertEquals(List.of(), selector.resolve("context", player, QuestTargetContext.none()));
        assertEquals(List.of(), selector.resolve("tag:nobody-has-this", player, QuestTargetContext.none()));
        assertNull(selector.resolveSingle("not-a-uuid", player, QuestTargetContext.none()));
    }
}
