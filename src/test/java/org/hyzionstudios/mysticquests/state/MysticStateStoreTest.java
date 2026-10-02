package org.hyzionstudios.mysticquests.state;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class MysticStateStoreTest {
    private final MysticQuestsEventBus eventBus = new MysticQuestsEventBus(null);
    private final MysticStateStore store = new MysticStateStore(eventBus);

    private static final StateKey PLAYER = StateKey.of(StateScope.PLAYER, "player-1");

    @Test
    void tagAndVariableRoundTrip() {
        assertTrue(store.addTag(PLAYER, "met_elder"));
        assertTrue(store.hasTag(PLAYER, "met_elder"));
        assertTrue(store.setVariable(PLAYER, "stage", "hunt"));
        assertEquals("hunt", store.variable(PLAYER, "stage"));

        assertTrue(store.removeTag(PLAYER, "met_elder"));
        assertFalse(store.hasTag(PLAYER, "met_elder"));
        assertTrue(store.removeVariable(PLAYER, "stage"));
        assertNull(store.variable(PLAYER, "stage"));
    }

    /**
     * Re-applying state a quest already set is extremely common. Those calls must report no change,
     * so they neither dirty the owner nor wake listeners.
     */
    @Test
    void redundantWritesReportNoChange() {
        store.addTag(PLAYER, "met_elder");
        store.setVariable(PLAYER, "stage", "hunt");
        store.drainDirty();

        assertFalse(store.addTag(PLAYER, "met_elder"));
        assertFalse(store.setVariable(PLAYER, "stage", "hunt"));
        assertFalse(store.removeTag(PLAYER, "never_had_this"));
        assertFalse(store.removeVariable(PLAYER, "never_set_this"));
        assertFalse(store.hasPendingChanges(), "no-op writes must not queue a disk write");
    }

    @Test
    void readingAnUnknownOwnerAllocatesNothingAndReturnsEmpty() {
        StateKey unknown = StateKey.of(StateScope.ENTITY, "nobody");
        assertEquals(Set.of(), store.tags(unknown));
        assertEquals(Map.of(), store.variables(unknown));
        assertFalse(store.hasTag(unknown, "anything"));
        assertFalse(store.hasPendingChanges(), "a read must never create state");
    }

    @Test
    void drainReturnsOneSnapshotPerTouchedOwnerAndClearsDirty() {
        store.addTag(PLAYER, "a");
        store.addTag(PLAYER, "b");
        store.setVariable(PLAYER, "stage", "hunt");
        store.setVariable(StateKey.global(), "festival", "open");

        List<StateSnapshot> drained = store.drainDirty();

        assertEquals(2, drained.size(), "three mutations on one owner coalesce into one snapshot");
        assertFalse(store.hasPendingChanges());
        assertTrue(store.drainDirty().isEmpty());
    }

    @Test
    void clearedOwnerDrainsAsAnEmptySnapshot() {
        store.addTag(PLAYER, "a");
        store.drainDirty();

        assertTrue(store.clearOwner(PLAYER));
        List<StateSnapshot> drained = store.drainDirty();

        assertEquals(1, drained.size());
        assertTrue(drained.get(0).isEmpty(), "an empty snapshot is what tells storage to delete rows");
    }

    /** Loading from disk must not immediately mark everything dirty and rewrite it. */
    @Test
    void loadDoesNotDirtyState() {
        store.load(List.of(new StateSnapshot(PLAYER, Set.of("met_elder"), Map.of("stage", "hunt"), Map.of())));

        assertTrue(store.hasTag(PLAYER, "met_elder"));
        assertEquals("hunt", store.variable(PLAYER, "stage"));
        assertFalse(store.hasPendingChanges());
    }

    @Test
    void incrementIsAtomicAcrossThreads() throws Exception {
        int threads = 8;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<java.util.concurrent.Future<?>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                futures.add(pool.submit(() -> {
                    start.await();
                    for (int n = 0; n < perThread; n++) {
                        store.incrementVariable(PLAYER, "kills", 1);
                    }
                    return null;
                }));
            }
            start.countDown();
            for (java.util.concurrent.Future<?> future : futures) {
                future.get(30, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdownNow();
        }

        assertEquals(Long.toString((long) threads * perThread), store.variable(PLAYER, "kills"));
    }

    @Test
    void mutationsPostEventsAndSubscriptionHandlesUnregister() throws Exception {
        AtomicInteger tagEvents = new AtomicInteger();
        AutoCloseable handle = eventBus.subscribe(StateEvents.TagChange.class, event -> tagEvents.incrementAndGet());

        store.addTag(PLAYER, "one");
        store.removeTag(PLAYER, "one");
        assertEquals(2, tagEvents.get());

        handle.close();
        store.addTag(PLAYER, "two");
        assertEquals(2, tagEvents.get(), "a closed handle must stop delivery");
    }
}
