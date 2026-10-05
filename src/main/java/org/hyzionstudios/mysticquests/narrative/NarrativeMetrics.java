package org.hyzionstudios.mysticquests.narrative;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.LongAdder;

/**
 * Counters and timings for the narrative runtime (2.0 specification §28, §29), read by
 * {@code /mquest narrative stats} and diagnostic exports.
 *
 * <p>Thread-safe and allocation-free on the hot path: counters are {@link LongAdder}s, and a timing
 * is three atomic updates on an entry created once per operation name. Operation names come from a
 * small fixed set ({@code action:<type>}, {@code condition}, {@code trigger.event}, ...), so the
 * map stays as small as the number of registered action and condition types.
 */
public final class NarrativeMetrics {
    /** An operation slower than this is counted as slow, and its caller reports it with context. */
    public static final long SLOW_NANOS = Duration.ofMillis(5).toNanos();

    public enum Counter {
        TRANSITIONS_COMPLETED,
        TRANSITIONS_FAILED,
        ACTIONS_FAILED,
        /** Custom conditions that threw; each reads as false. */
        CONDITION_FAULTS,
        /** Action or condition types used by loaded content that are no longer registered. */
        MISSING_REFERENCES,
        /** Errors found by content compiles, including reloads that were refused. */
        VALIDATION_ERRORS,
        TRIGGER_EVENTS,
        PUZZLE_INPUTS,
        PUZZLE_MISTAKES,
        PUZZLE_RESETS,
        /** Sessions read back from storage: after a join, a restart or a server transfer. */
        SESSIONS_RESTORED,
        SESSIONS_QUARANTINED,
        CUTSCENES_RECOVERED,
        SLOW_OPERATIONS,
        LIMITS_REACHED
    }

    /** How long one kind of operation has taken since the last reset. */
    public record Timing(String operation, long count, long totalNanos, long maxNanos) {
        public long meanNanos() {
            return count == 0 ? 0 : totalNanos / count;
        }
    }

    /** A current size, with the limit it is held to; {@code limit} is 0 when it has none. */
    public record Gauge(String name, long value, long limit) {
        public boolean over() {
            return limit > 0 && value > limit;
        }
    }

    public record Snapshot(Instant since, Instant at, Map<Counter, Long> counters, List<Timing> timings, List<Gauge> gauges) {
        public long count(Counter counter) {
            return counters.getOrDefault(counter, 0L);
        }

        /** Timings with the slowest single run first. */
        public List<Timing> slowest(int limit) {
            return timings.stream().sorted(Comparator.comparingLong(Timing::maxNanos).reversed()).limit(limit).toList();
        }
    }

    private static final class Stat {
        final LongAdder count = new LongAdder();
        final LongAdder total = new LongAdder();
        final AtomicLong max = new AtomicLong();
    }

    private final Clock clock;
    private final Map<Counter, LongAdder> counters = new EnumMap<>(Counter.class);
    private final Map<String, Stat> timings = new ConcurrentHashMap<>();
    private volatile Instant since;

    public NarrativeMetrics(Clock clock) {
        this.clock = clock;
        for (Counter counter : Counter.values()) {
            counters.put(counter, new LongAdder());
        }
        this.since = clock.instant();
    }

    public void increment(Counter counter) {
        counters.get(counter).increment();
    }

    public void add(Counter counter, long amount) {
        if (amount != 0) {
            counters.get(counter).add(amount);
        }
    }

    public long count(Counter counter) {
        return counters.get(counter).sum();
    }

    /**
     * Records how long an operation took.
     *
     * @return true when it was slow ({@link #SLOW_NANOS}); it is then already counted, and the
     *         caller should report it with the authored path that explains it
     */
    public boolean time(String operation, long nanos) {
        Stat stat = timings.computeIfAbsent(operation, ignored -> new Stat());
        stat.count.increment();
        stat.total.add(nanos);
        stat.max.accumulateAndGet(nanos, Math::max);
        if (nanos > SLOW_NANOS) {
            increment(Counter.SLOW_OPERATIONS);
            return true;
        }
        return false;
    }

    public Snapshot snapshot(List<Gauge> gauges) {
        Map<Counter, Long> counts = new EnumMap<>(Counter.class);
        counters.forEach((counter, adder) -> counts.put(counter, adder.sum()));
        List<Timing> timed = new ArrayList<>(timings.size());
        timings.forEach((operation, stat) ->
                timed.add(new Timing(operation, stat.count.sum(), stat.total.sum(), stat.max.get())));
        timed.sort(Comparator.comparing(Timing::operation));
        return new Snapshot(since, clock.instant(), Collections.unmodifiableMap(counts), List.copyOf(timed), List.copyOf(gauges));
    }

    /** Starts counting again from zero, for example before measuring one encounter. */
    public void reset() {
        counters.values().forEach(LongAdder::reset);
        timings.clear();
        since = clock.instant();
    }
}
