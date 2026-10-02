package org.hyzionstudios.mysticquests.narrative.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.function.UnaryOperator;

/**
 * Upgrades one kind of persisted document to the current schema version, one step at a time.
 *
 * <p>Every narrative document carries {@code schemaVersion} (§2: "all new persistent state must be
 * versioned and migratable"). Reading runs the chain of registered steps from the stored version up
 * to the current one. Two cases are refused outright rather than guessed at:
 *
 * <ul>
 *   <li>A document <b>newer</b> than this build understands. It was written by a later release, and
 *       loading it here would drop the fields this build does not know, then persist the loss.
 *       That matters on a network mid-rollout, where servers run different versions.</li>
 *   <li>A document with no version, or with a gap in the chain. Neither has a defined upgrade.</li>
 * </ul>
 */
public final class DocumentMigrator {
    public static final String VERSION_FIELD = "schemaVersion";

    /** The result of a successful read: the upgraded document and which steps ran. */
    public record Migrated(ObjectNode document, int fromVersion, List<Integer> appliedSteps) {
        public boolean changed() {
            return !appliedSteps.isEmpty();
        }
    }

    private final String kind;
    private final int currentVersion;
    private final Map<Integer, UnaryOperator<ObjectNode>> steps = new TreeMap<>();

    public DocumentMigrator(String kind, int currentVersion) {
        if (currentVersion < 1) {
            throw new IllegalArgumentException("Schema versions start at 1.");
        }
        this.kind = kind;
        this.currentVersion = currentVersion;
    }

    /**
     * Registers the step that upgrades a document from {@code fromVersion} to {@code fromVersion + 1}.
     * The step receives a copy and returns the upgraded document; it need not set the version field.
     */
    public DocumentMigrator step(int fromVersion, UnaryOperator<ObjectNode> step) {
        if (fromVersion < 1 || fromVersion >= currentVersion) {
            throw new IllegalArgumentException(kind + " has no step from version " + fromVersion + ".");
        }
        steps.put(fromVersion, step);
        return this;
    }

    public int currentVersion() {
        return currentVersion;
    }

    /** Stamps a freshly written document with the current version. */
    public ObjectNode stamp(ObjectNode document) {
        document.put(VERSION_FIELD, currentVersion);
        return document;
    }

    public Migrated migrate(ObjectNode stored) throws DocumentVersionException {
        JsonNode versionNode = stored.get(VERSION_FIELD);
        if (versionNode == null || !versionNode.canConvertToInt()) {
            throw new DocumentVersionException(kind + " document has no " + VERSION_FIELD + ".");
        }
        int version = versionNode.intValue();
        if (version > currentVersion) {
            throw new DocumentVersionException(kind + " document is schema version " + version
                    + " but this build only understands up to " + currentVersion
                    + "; it was written by a newer MysticQuests and is left untouched.");
        }
        if (version < 1) {
            throw new DocumentVersionException(kind + " document has invalid schema version " + version + ".");
        }
        ObjectNode document = stored.deepCopy();
        List<Integer> applied = new ArrayList<>();
        for (int from = version; from < currentVersion; from++) {
            UnaryOperator<ObjectNode> step = steps.get(from);
            if (step == null) {
                throw new DocumentVersionException(kind + " has no migration from schema version " + from + ".");
            }
            document = step.apply(document);
            applied.add(from);
        }
        document.put(VERSION_FIELD, currentVersion);
        return new Migrated(document, version, List.copyOf(applied));
    }

    /** A stored document this build cannot read safely. */
    public static final class DocumentVersionException extends Exception {
        public DocumentVersionException(String message) {
            super(message);
        }
    }
}
