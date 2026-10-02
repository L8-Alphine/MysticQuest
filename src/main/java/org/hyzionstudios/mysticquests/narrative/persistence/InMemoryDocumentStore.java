package org.hyzionstudios.mysticquests.narrative.persistence;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * {@link DocumentStore} held in memory. Documents are deep-copied in and out, so a test that keeps a
 * reference cannot observe or cause changes behind the runtime's back. That keeps "restart" tests
 * honest: a new runtime over the same store sees only what was actually written.
 */
public final class InMemoryDocumentStore implements DocumentStore {
    private final Map<String, Map<String, ObjectNode>> collections = new ConcurrentHashMap<>();

    @Override
    public Optional<ObjectNode> read(String collection, String id) {
        ObjectNode document = collection(collection).get(id);
        return document == null ? Optional.empty() : Optional.of(document.deepCopy());
    }

    @Override
    public void write(String collection, String id, ObjectNode document) {
        collection(collection).put(id, document.deepCopy());
    }

    @Override
    public void delete(String collection, String id) {
        collection(collection).remove(id);
    }

    @Override
    public List<String> list(String collection) {
        return collection(collection).keySet().stream().sorted().toList();
    }

    private Map<String, ObjectNode> collection(String name) {
        return collections.computeIfAbsent(name, ignored -> new ConcurrentHashMap<>());
    }
}
