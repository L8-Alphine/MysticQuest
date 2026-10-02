package org.hyzionstudios.mysticquests.narrative.persistence;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §22 and §23: versioned documents, safe filenames, no traversal. */
final class DocumentPersistenceTest {
    @TempDir
    Path root;

    private static ObjectNode document(String value) {
        return JsonNodeFactory.instance.objectNode().put("value", value);
    }

    @Test
    void hostileIdsStayInsideTheirCollectionAndRoundTrip() throws IOException {
        JsonDocumentStore store = new JsonDocumentStore(root, new ObjectMapper());
        List<String> ids = List.of("../../escape", "a/b\\c", "con", "CON", "player:9a3f|quest:x", "ünïcödé", "..", "%41");
        for (String id : ids) {
            store.write("state", id, document(id));
        }
        for (String id : ids) {
            assertEquals(id, store.read("state", id).orElseThrow().get("value").asText(), id);
        }
        assertEquals(ids.stream().sorted().toList(), store.list("state"), "names decode back to the exact ids");
        try (Stream<Path> everything = Files.walk(root)) {
            assertTrue(everything.filter(Files::isRegularFile)
                    .allMatch(file -> file.getParent().equals(root.resolve("state"))), "nothing escaped the collection");
        }
        assertFalse(Files.exists(root.getParent().resolve("escape.json")));
        assertThrows(IOException.class, () -> store.write("../x", "id", document("x")), "collections are validated too");
    }

    @Test
    void writesReplaceAndDeletesRemove() throws IOException {
        JsonDocumentStore store = new JsonDocumentStore(root, new ObjectMapper());
        store.write("sessions", "qs-1", document("first"));
        store.write("sessions", "qs-1", document("second"));
        assertEquals("second", store.read("sessions", "qs-1").orElseThrow().get("value").asText());
        store.delete("sessions", "qs-1");
        assertTrue(store.read("sessions", "qs-1").isEmpty());
        assertTrue(store.list("missing").isEmpty());
    }

    @Test
    void migratorUpgradesStepByStepAndRefusesWhatItCannotRead() throws Exception {
        DocumentMigrator migrator = new DocumentMigrator("thing", 3)
                .step(1, doc -> doc.put("renamed", doc.remove("old").asText()))
                .step(2, doc -> doc.put("added", true));
        ObjectNode v1 = JsonNodeFactory.instance.objectNode().put("schemaVersion", 1).put("old", "x");
        DocumentMigrator.Migrated migrated = migrator.migrate(v1);
        assertEquals(List.of(1, 2), migrated.appliedSteps());
        assertEquals("x", migrated.document().get("renamed").asText());
        assertTrue(migrated.document().get("added").asBoolean());
        assertEquals(3, migrated.document().get("schemaVersion").intValue());
        assertEquals(1, v1.get("schemaVersion").intValue(), "the stored document is not mutated");

        assertThrows(DocumentMigrator.DocumentVersionException.class,
                () -> migrator.migrate(JsonNodeFactory.instance.objectNode().put("schemaVersion", 4)), "newer than this build");
        assertThrows(DocumentMigrator.DocumentVersionException.class,
                () -> migrator.migrate(JsonNodeFactory.instance.objectNode()), "unversioned");
        assertThrows(DocumentMigrator.DocumentVersionException.class,
                () -> new DocumentMigrator("gap", 3).step(1, doc -> doc).migrate(
                        JsonNodeFactory.instance.objectNode().put("schemaVersion", 1)), "a gap in the chain");
    }
}
