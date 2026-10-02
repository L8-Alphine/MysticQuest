package org.hyzionstudios.mysticquests.narrative.persistence;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.ByteArrayOutputStream;
import java.io.FileNotFoundException;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.NoSuchFileException;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * {@link DocumentStore} backed by one pretty-printed JSON file per document.
 *
 * <p>Human-readable on purpose: when a player's story is stuck, staff can open the session file and
 * see exactly what the runtime sees.
 *
 * <h2>Filenames</h2>
 *
 * <p>Ids contain characters that are unsafe or ambiguous in paths ({@code /}, {@code :}, {@code ..},
 * upper case on case-insensitive filesystems, Windows device names such as {@code con}). Every
 * character outside {@code [a-z0-9_-]} is therefore percent-encoded, and every name gets a fixed
 * prefix. An id can then never produce a separator, a traversal or a reserved name, and decoding
 * stays exact. The resolved path is also checked against the collection directory as a second line
 * of defence (§23: no path traversal through generated names).
 */
public final class JsonDocumentStore implements DocumentStore {
    private static final String PREFIX = "d-";
    private static final String SUFFIX = ".json";
    private static final Pattern COLLECTION = Pattern.compile("[a-z0-9][a-z0-9-]*");

    private final Path root;
    private final ObjectMapper mapper;

    public JsonDocumentStore(Path root, ObjectMapper mapper) throws IOException {
        this.root = root.toAbsolutePath().normalize();
        this.mapper = mapper;
        Files.createDirectories(this.root);
    }

    @Override
    public Optional<ObjectNode> read(String collection, String id) throws IOException {
        Path file = file(collection, id);
        try {
            JsonNode node = mapper.readTree(file.toFile());
            if (node == null || !node.isObject()) {
                throw new IOException("Document " + collection + "/" + id + " is not a JSON object.");
            }
            return Optional.of((ObjectNode) node);
        } catch (NoSuchFileException | FileNotFoundException missing) {
            return Optional.empty();
        }
    }

    @Override
    public void write(String collection, String id, ObjectNode document) throws IOException {
        Path file = file(collection, id);
        Files.createDirectories(file.getParent());
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        mapper.writerWithDefaultPrettyPrinter().writeValue(bytes, document);
        Path temp = Files.createTempFile(file.getParent(), ".tmp-", SUFFIX);
        try {
            Files.write(temp, bytes.toByteArray());
            try {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temp, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temp);
        }
    }

    @Override
    public void delete(String collection, String id) throws IOException {
        Files.deleteIfExists(file(collection, id));
    }

    @Override
    public List<String> list(String collection) throws IOException {
        Path directory = collectionDirectory(collection);
        if (!Files.isDirectory(directory)) {
            return List.of();
        }
        List<String> ids = new ArrayList<>();
        try (Stream<Path> files = Files.list(directory)) {
            for (Path file : files.toList()) {
                String name = file.getFileName().toString();
                if (name.startsWith(PREFIX) && name.endsWith(SUFFIX)) {
                    ids.add(decode(name.substring(PREFIX.length(), name.length() - SUFFIX.length())));
                }
            }
        }
        ids.sort(String::compareTo);
        return ids;
    }

    private Path file(String collection, String id) throws IOException {
        if (id == null || id.isEmpty()) {
            throw new IOException("Document id must not be empty.");
        }
        Path directory = collectionDirectory(collection);
        Path file = directory.resolve(PREFIX + encode(id) + SUFFIX).normalize();
        if (!file.getParent().equals(directory)) {
            throw new IOException("Document id '" + id + "' resolves outside its collection.");
        }
        return file;
    }

    private Path collectionDirectory(String collection) throws IOException {
        if (collection == null || !COLLECTION.matcher(collection).matches()) {
            throw new IOException("Invalid document collection '" + collection + "'.");
        }
        return root.resolve(collection);
    }

    static String encode(String id) {
        StringBuilder encoded = new StringBuilder(id.length());
        for (byte value : id.getBytes(StandardCharsets.UTF_8)) {
            char character = (char) (value & 0xFF);
            if ((character >= 'a' && character <= 'z') || (character >= '0' && character <= '9')
                    || character == '_' || character == '-') {
                encoded.append(character);
            } else {
                encoded.append('%').append(String.format("%02X", value & 0xFF));
            }
        }
        return encoded.toString();
    }

    static String decode(String encoded) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream(encoded.length());
        for (int index = 0; index < encoded.length(); index++) {
            char character = encoded.charAt(index);
            if (character == '%' && index + 2 < encoded.length()) {
                bytes.write(Integer.parseInt(encoded.substring(index + 1, index + 3), 16));
                index += 2;
            } else {
                bytes.write(character);
            }
        }
        return bytes.toString(StandardCharsets.UTF_8);
    }
}
