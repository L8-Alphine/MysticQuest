package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;
import org.hyzionstudios.mysticquests.narrative.persistence.InMemoryDocumentStore;
import org.hyzionstudios.mysticquests.narrative.session.PartyExitPolicy;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Builds a {@link NarrativeRuntime} over an in-memory store, with a controllable clock and party map.
 * {@link #restart()} builds a fresh runtime over the same store, which is how tests prove that state
 * survives a restart rather than living on in memory.
 */
public final class NarrativeTestKit implements AutoCloseable {
    public static final ObjectMapper JSON = new ObjectMapper();

    public final DocumentStore store;
    public final MutableClock clock;
    public final Map<UUID, String> parties = new ConcurrentHashMap<>();
    public final List<String> problems = new ArrayList<>();
    public final List<String> signals = new ArrayList<>();
    public final List<String> audits = new ArrayList<>();
    private final ScopeSupport scopes;
    private final PartyExitPolicy exitPolicy;
    private Map<String, JsonNode> lastSections = Map.of();
    private NarrativeRuntime runtime;

    public NarrativeTestKit() {
        this(ScopeSupport.standard(true), PartyExitPolicy.FORK);
    }

    public NarrativeTestKit(ScopeSupport scopes, PartyExitPolicy exitPolicy) {
        this.store = new InMemoryDocumentStore();
        this.clock = new MutableClock(Instant.parse("2026-10-02T12:00:00Z"));
        this.scopes = scopes;
        this.exitPolicy = exitPolicy;
        this.runtime = build();
    }

    private NarrativeRuntime build() {
        return new NarrativeRuntime(new NarrativeRuntime.Settings(
                store, clock, "test-server", "test-network", scopes,
                player -> Optional.ofNullable(parties.get(player)),
                List.of(),
                (player, signal, amount) -> signals.add(player + " " + signal + " x" + amount),
                exitPolicy,
                0L,
                problems::add,
                audits::add));
    }

    public NarrativeRuntime runtime() {
        return runtime;
    }

    /** Compiles and installs one package's narrative sections from JSON; fails the test on errors. */
    public DiagnosticReport load(String packageJson) {
        return loadPackages(Map.of("test", packageJson));
    }

    public DiagnosticReport loadPackages(Map<String, String> packages) {
        Map<String, JsonNode> sections = new java.util.LinkedHashMap<>();
        packages.forEach((id, json) -> sections.put(id, parse(json)));
        lastSections = sections;
        DiagnosticReport report = runtime.reload(sections, Map.of());
        if (report.hasErrors()) {
            throw new AssertionError("content failed to compile:\n" + report.format());
        }
        return report;
    }

    /** Compiles without installing and returns the report, for tests that expect errors. */
    public DiagnosticReport compile(String packageJson) {
        DiagnosticReport report = new DiagnosticReport();
        runtime.compile(Map.of("test", parse(packageJson)), Map.of(), report);
        return report;
    }

    /** Flushes, discards the runtime, and builds a new one over the same store and content. */
    public NarrativeRuntime restart() {
        runtime.close();
        runtime = build();
        DiagnosticReport report = runtime.reload(lastSections, Map.of());
        if (report.hasErrors()) {
            throw new AssertionError(report.format());
        }
        return runtime;
    }

    public static JsonNode parse(String json) {
        try {
            return JSON.readTree(json);
        } catch (IOException invalid) {
            throw new UncheckedIOException(invalid);
        }
    }

    public static NamespacedId id(String raw) {
        return NamespacedId.parse(raw);
    }

    @Override
    public void close() {
        runtime.close();
    }

    /** A clock tests move by hand. */
    public static final class MutableClock extends Clock {
        private volatile Instant now;

        public MutableClock(Instant start) {
            this.now = start;
        }

        public void advance(Duration duration) {
            now = now.plus(duration);
        }

        @Override
        public ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }
}
