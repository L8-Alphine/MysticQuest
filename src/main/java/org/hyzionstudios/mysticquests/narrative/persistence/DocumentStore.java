package org.hyzionstudios.mysticquests.narrative.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.util.List;
import java.util.Optional;

/**
 * Durable storage for narrative documents: session state, owner state, indexes.
 *
 * <p>Deliberately small (read, write, delete, list), so a deployment can put it on a shared
 * filesystem, a database, or a network store without the runtime knowing which. Server transfer
 * (§22) works by pointing several servers at the same store. The runtime never holds a document open
 * across a write, so the store does not need to coordinate writers beyond making each write atomic.
 *
 * <p>Ids are arbitrary strings, including {@code :} and {@code /}. Implementations must encode them
 * safely; an id must never be able to address anything outside its collection.
 */
public interface DocumentStore {
    Optional<ObjectNode> read(String collection, String id) throws IOException;

    /** Replaces the document atomically: a concurrent reader sees the old one or the new one. */
    void write(String collection, String id, ObjectNode document) throws IOException;

    void delete(String collection, String id) throws IOException;

    /** Ids of every document in a collection. Used at startup and by admin tooling, not hot paths. */
    List<String> list(String collection) throws IOException;
}
