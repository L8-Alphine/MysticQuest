package org.hyzionstudios.mysticquests.state;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;
import org.hyzionstudios.mysticquests.storage.QuestStorage;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class StateWriteQueueTest {
    private static final StateKey PLAYER = StateKey.of(StateScope.PLAYER, "player-1");

    @Test
    void burstsOfMutationsCoalesceIntoOneWritePerOwner() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        MysticStateStore store = new MysticStateStore(new MysticQuestsEventBus(null));

        try (StateWriteQueue queue = new StateWriteQueue(store, storage, 100, null)) {
            // The pattern that used to rewrite the whole table 50 times.
            for (int i = 0; i < 50; i++) {
                store.incrementVariable(PLAYER, "kills", 1);
            }
            queue.flush();
        }

        assertEquals(1, storage.writeCount.get(), "50 mutations of one owner should cost one write");
        assertEquals(1, storage.lastBatchSize, "and that write should carry a single owner snapshot");
        assertEquals("50", storage.finalValue(PLAYER, "kills"));
    }

    @Test
    void flushIsANoOpWhenNothingChanged() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        MysticStateStore store = new MysticStateStore(new MysticQuestsEventBus(null));

        try (StateWriteQueue queue = new StateWriteQueue(store, storage, 100, null)) {
            queue.flush();
            queue.flush();
        }

        assertEquals(0, storage.writeCount.get(), "an idle server must not write");
    }

    /** Closing must drain whatever is still pending, even if the interval has not elapsed. */
    @Test
    void closeFlushesPendingChanges() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        MysticStateStore store = new MysticStateStore(new MysticQuestsEventBus(null));

        StateWriteQueue queue = new StateWriteQueue(store, storage, 60_000, null);
        store.addTag(PLAYER, "met_elder");
        queue.close();

        assertEquals(1, storage.writeCount.get());
        assertTrue(storage.written.get(0).stream()
                .anyMatch(snapshot -> snapshot.key().equals(PLAYER) && snapshot.tags().contains("met_elder")));
    }

    /** The scheduled writer picks changes up on its own without anyone calling flush. */
    @Test
    void scheduledTickWritesWithoutAnExplicitFlush() throws Exception {
        RecordingStorage storage = new RecordingStorage();
        MysticStateStore store = new MysticStateStore(new MysticQuestsEventBus(null));

        try (StateWriteQueue queue = new StateWriteQueue(store, storage, StateWriteQueue.MIN_INTERVAL_MS, null)) {
            store.addTag(PLAYER, "met_elder");
            long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(10);
            while (storage.writeCount.get() == 0 && System.nanoTime() < deadline) {
                Thread.sleep(20);
            }
        }

        assertTrue(storage.writeCount.get() >= 1, "the writer thread should have flushed on its own");
    }

    private static final class RecordingStorage implements QuestStorage {
        private final AtomicInteger writeCount = new AtomicInteger();
        private final List<List<StateSnapshot>> written = new CopyOnWriteArrayList<>();
        private volatile int lastBatchSize;

        @Override
        public PlayerQuestData loadPlayer(UUID playerId) {
            return new PlayerQuestData(playerId);
        }

        @Override
        public void savePlayer(PlayerQuestData data) {
        }

        @Override
        public List<StateSnapshot> loadState() {
            return List.of();
        }

        @Override
        public void writeState(Collection<StateSnapshot> snapshots) {
            writeCount.incrementAndGet();
            lastBatchSize = snapshots.size();
            written.add(new ArrayList<>(snapshots));
        }

        String finalValue(StateKey key, String name) {
            for (int i = written.size() - 1; i >= 0; i--) {
                for (StateSnapshot snapshot : written.get(i)) {
                    if (snapshot.key().equals(key) && snapshot.variables().containsKey(name)) {
                        return snapshot.variables().get(name);
                    }
                }
            }
            return null;
        }

        @Override
        public void close() throws IOException {
        }
    }
}
