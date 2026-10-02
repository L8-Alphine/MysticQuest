package org.hyzionstudios.mysticquests.state;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.junit.jupiter.api.Test;

import java.util.concurrent.atomic.AtomicReference;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The public API lets any mod pass a variable value straight through, so a null must be handled
 * rather than reaching the concurrent map or the change comparison.
 */
final class StateNullValueTest {
    private final MysticQuestsEventBus eventBus = new MysticQuestsEventBus(null);
    private final MysticStateStore store = new MysticStateStore(eventBus);
    private static final StateKey PLAYER = StateKey.of(StateScope.PLAYER, "player-1");

    @Test
    void nullVariableValueIsStoredAsEmpty() {
        assertTrue(store.setVariable(PLAYER, "note", null));
        assertEquals("", store.variable(PLAYER, "note"));
    }

    @Test
    void nullFollowedByEmptyIsNotACHange() {
        store.setVariable(PLAYER, "note", null);
        store.drainDirty();

        assertFalse(store.setVariable(PLAYER, "note", ""));
        assertFalse(store.hasPendingChanges());
    }

    @Test
    void changeEventCarriesTheStoredValueNotTheNull() {
        AtomicReference<StateEvents.VariableChange> seen = new AtomicReference<>();
        eventBus.subscribe(StateEvents.VariableChange.class, seen::set);

        store.setVariable(PLAYER, "note", null);

        assertNotNull(seen.get());
        assertEquals("", seen.get().value());
    }
}
