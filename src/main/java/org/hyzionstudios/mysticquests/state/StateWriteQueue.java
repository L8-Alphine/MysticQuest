package org.hyzionstudios.mysticquests.state;

import org.hyzionstudios.mysticquests.storage.QuestStorage;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.Executors;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import java.util.logging.Level;

/**
 * Moves state persistence off the game thread and coalesces bursts into one write.
 *
 * <p>Quest scripting mutates state in bursts — completing an objective can add several tags and bump
 * a few counters in the same tick. Previously each of those calls wrote the entire scoped-state table
 * synchronously. Here they only mark owners dirty; this queue wakes on a fixed interval, drains
 * whatever accumulated, and writes each touched owner once.
 *
 * <p>Durability comes from {@link #flush()}, which runs the drain on the calling thread. It is called
 * on player disconnect and on shutdown, so nothing is lost to the debounce window. Writes are
 * serialised by a lock so a scheduled flush and an explicit one cannot interleave and write an
 * owner's rows out of order.
 */
public final class StateWriteQueue implements AutoCloseable {
    /** Floor on the flush interval, so a misconfigured value cannot spin the writer thread hot. */
    static final long MIN_INTERVAL_MS = 100L;

    private final MysticStateStore store;
    private final QuestStorage storage;

    /** Null outside a running server, where there is no plugin logger to report a failed write to. */
    @Nullable
    private final HytaleLogger logger;

    private final ScheduledExecutorService executor;
    private final ReentrantLock writeLock = new ReentrantLock();
    private volatile boolean closed;

    public StateWriteQueue(
            MysticStateStore store,
            QuestStorage storage,
            long intervalMillis,
            @Nullable HytaleLogger logger) {
        this.store = store;
        this.storage = storage;
        this.logger = logger;
        long interval = Math.max(MIN_INTERVAL_MS, intervalMillis);
        this.executor = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-StateWriter");
            thread.setDaemon(true);
            return thread;
        });
        this.executor.scheduleWithFixedDelay(this::flushQuietly, interval, interval, TimeUnit.MILLISECONDS);
    }

    /**
     * Writes every pending change on the calling thread. Safe to call when nothing is pending —
     * it returns without touching storage.
     */
    public void flush() {
        if (!store.hasPendingChanges()) {
            return;
        }
        writeLock.lock();
        try {
            List<StateSnapshot> pending = store.drainDirty();
            if (pending.isEmpty()) {
                return;
            }
            storage.writeState(pending);
        } catch (IOException exception) {
            log(exception, "Failed to persist MysticQuests state.");
        } finally {
            writeLock.unlock();
        }
    }

    /**
     * Requests an out-of-band flush on the writer thread. Used after bulk operations that should not
     * block the caller but should not wait a full interval either.
     */
    public void requestFlush() {
        if (closed) {
            flush();
            return;
        }
        try {
            executor.execute(this::flushQuietly);
        } catch (RejectedExecutionException shuttingDown) {
            flush();
        }
    }

    private void flushQuietly() {
        try {
            flush();
        } catch (RuntimeException exception) {
            log(exception, "MysticQuests state writer tick failed.");
        }
    }

    private void log(Exception exception, String message) {
        if (logger != null) {
            logger.at(Level.WARNING).withCause(exception).log(message);
        }
    }

    @Override
    public void close() {
        closed = true;
        executor.shutdown();
        try {
            if (!executor.awaitTermination(5, TimeUnit.SECONDS)) {
                executor.shutdownNow();
            }
        } catch (InterruptedException interrupted) {
            executor.shutdownNow();
            Thread.currentThread().interrupt();
        }
        // Final synchronous drain, after the scheduler has stopped, so shutdown never loses a change.
        flush();
    }
}
