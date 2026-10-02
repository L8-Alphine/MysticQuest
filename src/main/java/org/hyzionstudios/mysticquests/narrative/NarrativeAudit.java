package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.persistence.DocumentMigrator;
import org.hyzionstudios.mysticquests.narrative.persistence.DocumentStore;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Clock;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * Append-only record of staff interventions that change story state (§21.1 "Auditability").
 *
 * <p>Each entry carries the actor, target, time, before/after summary, reason, server, and session,
 * and becomes its own document, which is never rewritten. Entries are never updated. A failed write
 * is reported, never retried silently into a different record.
 */
public final class NarrativeAudit {
    static final String COLLECTION = "audit";

    private final DocumentStore documents;
    private final Clock clock;
    private final String serverId;
    private final Consumer<String> sink;
    private final DocumentMigrator versions = new DocumentMigrator("audit entry", 1);
    private final AtomicLong sequence = new AtomicLong();

    /** @param sink receives a one-line copy of every entry, for the server log */
    public NarrativeAudit(DocumentStore documents, Clock clock, String serverId, Consumer<String> sink) {
        this.documents = documents;
        this.clock = clock;
        this.serverId = serverId;
        this.sink = sink;
    }

    /**
     * @param actor who did it: a staff UUID, {@code console}, or a system name
     * @param action what was done, such as {@code puzzle.reset}
     * @param target whose story it changed
     */
    public void record(String actor, String action, String target, @Nullable String sessionId,
                       String before, String after, @Nullable String reason) {
        Instant now = clock.instant();
        ObjectNode entry = versions.stamp(JsonNodeFactory.instance.objectNode());
        entry.put("at", now.toString());
        entry.put("server", serverId);
        entry.put("actor", actor);
        entry.put("action", action);
        entry.put("target", target);
        if (sessionId != null) {
            entry.put("session", sessionId);
        }
        entry.put("before", before);
        entry.put("after", after);
        if (reason != null && !reason.isBlank()) {
            entry.put("reason", reason);
        }
        String id = now.toEpochMilli() + "-" + serverId + "-" + sequence.incrementAndGet();
        sink.accept("audit " + action + " by " + actor + " on " + target
                + (sessionId == null ? "" : " session " + sessionId) + ": " + before + " -> " + after
                + (reason == null || reason.isBlank() ? "" : " (" + reason + ")"));
        try {
            documents.write(COLLECTION, id, entry);
        } catch (IOException failure) {
            sink.accept("audit entry " + id + " could not be stored: " + failure.getMessage());
        }
    }
}
