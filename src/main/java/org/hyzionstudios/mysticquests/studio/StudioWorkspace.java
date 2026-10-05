package org.hyzionstudios.mysticquests.studio;

import com.fasterxml.jackson.core.util.DefaultIndenter;
import com.fasterxml.jackson.core.util.DefaultPrettyPrinter;
import com.fasterxml.jackson.core.util.Separators;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.dataformat.yaml.YAMLFactory;
import com.fasterxml.jackson.dataformat.yaml.YAMLGenerator;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeSet;
import java.util.stream.Stream;

/**
 * The Studio's draft: a private copy of the live content that creators edit, validate and publish
 * (§23: editing and publishing are separate). Nothing written here reaches players until a release
 * is published.
 *
 * <p>Every file has a version (a content hash, sent as an ETag). A write names the version it was
 * based on, and is refused when the file changed since, so two creators never overwrite each other
 * silently. The draft also remembers the live content it was based on, so publishing can refuse to
 * revert edits made to the live files outside the Studio (by hand or with the in-game editor).
 */
public final class StudioWorkspace {
    /** Largest content file the Studio accepts, in bytes. */
    public static final int MAX_FILE_BYTES = 512 * 1024;

    public record FileInfo(String path, long size, String version) {
    }

    public record FileContent(String path, String text, String version) {
    }

    public record Change(String path, Kind kind) {
        public enum Kind { ADDED, CHANGED, REMOVED }
    }

    private final ContentRoot live;
    private final ContentRoot draft;
    private final Path baseFile;
    private final ObjectMapper json;
    private final ObjectMapper yaml;
    /** Two-space indents, {@code "key": value}, one item per line and LF line ends, as content is written by hand. */
    private static final DefaultPrettyPrinter JSON_STYLE = new DefaultPrettyPrinter()
            .withObjectIndenter(new DefaultIndenter("  ", "\n"))
            .withArrayIndenter(new DefaultIndenter("  ", "\n"))
            .withSeparators(Separators.createDefaultInstance().withObjectFieldValueSpacing(Separators.Spacing.AFTER));
    /** Writes YAML for form edits; every string is quoted, so "123" or "yes" never turns into a number or boolean. */
    private final ObjectMapper yamlWriter = new ObjectMapper(YAMLFactory.builder()
            .disable(YAMLGenerator.Feature.WRITE_DOC_START_MARKER).build());

    /**
     * @param live the content players are running
     * @param studioRoot the Studio's own folder; the draft lives in {@code workspace/} under it
     */
    public StudioWorkspace(ContentRoot live, Path studioRoot, ObjectMapper json, ObjectMapper yaml) {
        this.live = live;
        this.draft = ContentRoot.under(studioRoot.resolve("workspace"));
        this.baseFile = studioRoot.resolve("workspace-base.json");
        this.json = json;
        this.yaml = yaml;
    }

    public ContentRoot draft() {
        return draft;
    }

    public ContentRoot live() {
        return live;
    }

    /** Creates the draft from the live content the first time the Studio is used. */
    public synchronized void ensure() throws IOException {
        if (Files.isDirectory(draft.packages()) || Files.isDirectory(draft.templates())) {
            return;
        }
        Files.createDirectories(draft.packages());
        live.mirrorTo(draft);
        rebase();
    }

    public synchronized List<FileInfo> files() throws IOException {
        ensure();
        List<FileInfo> files = new ArrayList<>();
        for (Map.Entry<String, String> entry : draft.hashes().entrySet()) {
            files.add(new FileInfo(entry.getKey(), Files.size(draft.file(entry.getKey())), entry.getValue()));
        }
        return files;
    }

    /** @throws StudioException {@code NOT_FOUND} when the draft has no such file */
    public synchronized FileContent read(String path) throws IOException, StudioException {
        ensure();
        Path file = draft.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "No file " + path + " in the draft.");
        }
        byte[] bytes = Files.readAllBytes(file);
        return new FileContent(path, new String(bytes, StandardCharsets.UTF_8), ContentRoot.hash(bytes));
    }

    /** The current text of a draft file, or null when it does not exist. */
    @Nullable
    public synchronized String textOrNull(String path) throws IOException, StudioException {
        ensure();
        Path file = draft.resolve(path);
        return Files.isRegularFile(file) ? Files.readString(file) : null;
    }

    /**
     * Writes a draft file.
     *
     * @param expectedVersion the version the edit was based on, or null to create a new file
     * @return the file's new version
     * @throws StudioException {@code CONFLICT} when the file changed since {@code expectedVersion} (or
     *         exists, for a create); {@code BAD_REQUEST} or {@code TOO_LARGE} for unusable content
     */
    public synchronized String write(String path, String text, @Nullable String expectedVersion) throws IOException, StudioException {
        ensure();
        Path file = draft.resolve(path);
        byte[] bytes = (text == null ? "" : text).getBytes(StandardCharsets.UTF_8);
        if (bytes.length > MAX_FILE_BYTES) {
            throw new StudioException(StudioException.Status.TOO_LARGE, "Files are limited to " + (MAX_FILE_BYTES / 1024) + " KiB.");
        }
        parse(path, text);
        checkVersion(path, file, expectedVersion);
        ContentRoot.writeAtomically(file, bytes);
        return ContentRoot.hash(bytes);
    }

    public synchronized void delete(String path, String expectedVersion) throws IOException, StudioException {
        ensure();
        Path file = draft.resolve(path);
        if (!Files.isRegularFile(file)) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "No file " + path + " in the draft.");
        }
        checkVersion(path, file, expectedVersion);
        draft.remove(path);
    }

    private void checkVersion(String path, Path file, @Nullable String expectedVersion) throws IOException, StudioException {
        boolean exists = Files.isRegularFile(file);
        if (expectedVersion == null || expectedVersion.isBlank()) {
            if (exists) {
                throw new StudioException(StudioException.Status.CONFLICT, path + " already exists; open it to edit it.");
            }
            return;
        }
        if (!exists || !ContentRoot.hash(Files.readAllBytes(file)).equals(expectedVersion)) {
            throw new StudioException(StudioException.Status.CONFLICT,
                    path + " was changed by someone else since you opened it. Reload it and apply your edit again.");
        }
    }

    /** Parses a file's text with the parser its extension calls for, refusing anything but an object or array. */
    public JsonNode parse(String path, String text) throws StudioException {
        ObjectMapper parser = parser(path);
        try {
            JsonNode parsed = parser.readTree(text == null ? "" : text);
            if (parsed == null || !parsed.isContainerNode()) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, path + " must hold an object or a list.");
            }
            return parsed;
        } catch (IOException invalid) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, path + " is not valid "
                    + (parser == json ? "JSON" : "YAML") + ": " + firstLine(invalid.getMessage()));
        }
    }

    /**
     * Turns an edited document back into file text in the file's own format, for the Studio's form
     * editors. YAML comments do not survive this; the Studio warns before a form save drops them.
     */
    public String format(String path, JsonNode document) throws StudioException {
        if (document == null || !document.isContainerNode()) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "A document must be an object or a list.");
        }
        try {
            return path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")
                    ? json.writer(JSON_STYLE).writeValueAsString(document) + "\n"
                    : yamlWriter.writeValueAsString(document);
        } catch (IOException unwritable) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The document could not be written: " + unwritable.getMessage());
        }
    }

    /** Whether a YAML file has comments a form save would drop. */
    public static boolean hasComments(String path, String text) {
        if (path.toLowerCase(java.util.Locale.ROOT).endsWith(".json")) {
            return false;
        }
        for (String line : text.split("\n")) {
            String stripped = line.strip();
            if (stripped.startsWith("#") || stripped.contains(" #")) {
                return true;
            }
        }
        return false;
    }

    public ObjectMapper parser(String path) {
        return path.toLowerCase(java.util.Locale.ROOT).endsWith(".json") ? json : yaml;
    }

    /** What publishing the draft would change in the live content. */
    public synchronized List<Change> changes() throws IOException {
        ensure();
        return diff(live.hashes(), draft.hashes());
    }

    /** Live files edited outside the Studio since the draft was based on them. */
    public synchronized List<Change> liveDrift() throws IOException {
        ensure();
        return diff(readBase(), live.hashes());
    }

    static List<Change> diff(Map<String, String> from, Map<String, String> to) {
        List<Change> changes = new ArrayList<>();
        TreeSet<String> paths = new TreeSet<>(from.keySet());
        paths.addAll(to.keySet());
        for (String path : paths) {
            String before = from.get(path);
            String after = to.get(path);
            if (before == null) {
                changes.add(new Change(path, Change.Kind.ADDED));
            } else if (after == null) {
                changes.add(new Change(path, Change.Kind.REMOVED));
            } else if (!before.equals(after)) {
                changes.add(new Change(path, Change.Kind.CHANGED));
            }
        }
        return changes;
    }

    /** Throws the draft away and starts again from the live content. */
    public synchronized void reset() throws IOException {
        deleteTree(draft.packages());
        deleteTree(draft.templates());
        ensure();
    }

    /** Replaces the draft's content with a snapshot (a past release), to publish it again. */
    public synchronized void loadFrom(ContentRoot snapshot) throws IOException {
        ensure();
        snapshot.mirrorTo(draft);
    }

    /** Marks the current live content as what the draft is based on; called after a publish. */
    public synchronized void rebase() throws IOException {
        ObjectNode base = json.createObjectNode();
        ObjectNode files = base.putObject("files");
        live.hashes().forEach(files::put);
        ContentRoot.writeAtomically(baseFile, json.writerWithDefaultPrettyPrinter().writeValueAsBytes(base));
    }

    private Map<String, String> readBase() throws IOException {
        Map<String, String> base = new LinkedHashMap<>();
        if (Files.isRegularFile(baseFile)) {
            json.readTree(baseFile.toFile()).path("files").fields()
                    .forEachRemaining(entry -> base.put(entry.getKey(), entry.getValue().asText()));
        }
        return base;
    }

    static void deleteTree(Path root) throws IOException {
        if (!Files.exists(root)) {
            return;
        }
        try (Stream<Path> paths = Files.walk(root)) {
            for (Path path : paths.sorted(Comparator.reverseOrder()).toList()) {
                Files.delete(path);
            }
        }
    }

    private static String firstLine(String message) {
        if (message == null) {
            return "unreadable";
        }
        int newline = message.indexOf('\n');
        return newline < 0 ? message : message.substring(0, newline);
    }
}
