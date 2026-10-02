package org.hyzionstudios.mysticquests.narrative.cutscene;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.session.SessionComponent;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Instant;
import java.util.UUID;

/**
 * The cutscene a session is playing, stored with the session so that a restart or disconnect
 * finds it and finishes it properly (§22 "crash recovery for in-progress cutscenes").
 *
 * <p>{@code runId} is new for every play and keys the ledger, so each step of one play runs exactly
 * once, while playing the scene again later runs it again.
 */
public final class CutsceneRun implements SessionComponent {
    private final NamespacedId cutscene;
    private final String runId;
    private final Instant startedAt;
    private final UUID actor;
    private int next;
    private boolean skipping;

    public CutsceneRun(NamespacedId cutscene, String runId, Instant startedAt, UUID actor) {
        this.cutscene = cutscene;
        this.runId = runId;
        this.startedAt = startedAt;
        this.actor = actor;
    }

    public NamespacedId cutscene() {
        return cutscene;
    }

    public String runId() {
        return runId;
    }

    public Instant startedAt() {
        return startedAt;
    }

    /** The player whose world thread advances the scene and whose camera v1 steps address. */
    public UUID actor() {
        return actor;
    }

    /** Index of the first step not yet settled. */
    public int next() {
        return next;
    }

    void advanceTo(int next) {
        this.next = next;
    }

    /** True once the scene is being finished early: remaining cosmetic steps are dropped. */
    public boolean skipping() {
        return skipping;
    }

    void markSkipping() {
        this.skipping = true;
    }

    @Override
    public ObjectNode toJson() {
        ObjectNode node = JsonNodeFactory.instance.objectNode();
        node.put("cutscene", cutscene.toString());
        node.put("run", runId);
        node.put("startedAt", startedAt.toString());
        node.put("actor", actor.toString());
        node.put("next", next);
        node.put("skipping", skipping);
        return node;
    }

    public static CutsceneRun fromJson(ObjectNode node) {
        CutsceneRun run = new CutsceneRun(
                NamespacedId.parse(node.path("cutscene").asText()),
                node.path("run").asText(),
                Instant.parse(node.path("startedAt").asText()),
                UUID.fromString(node.path("actor").asText()));
        run.next = node.path("next").asInt(0);
        run.skipping = node.path("skipping").asBoolean(false);
        return run;
    }
}
