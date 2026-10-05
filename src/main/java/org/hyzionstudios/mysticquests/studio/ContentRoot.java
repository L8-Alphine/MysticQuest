package org.hyzionstudios.mysticquests.studio;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.regex.Pattern;
import java.util.stream.Stream;

/**
 * A set of quest content on disk: a packages folder and its templates folder, addressed by logical
 * paths such as {@code packages/greenvale/quests.yml}. The live content, the Studio draft and every
 * release snapshot are content roots, so copying between them is one operation.
 *
 * <p>Only content files (YAML and JSON) are listed, copied or removed; anything else in the folders
 * is left alone. Logical paths are validated before they are resolved (§23 "prevent path
 * traversal"): fixed top folders, plain segment names, no hidden files, a content extension.
 */
public record ContentRoot(Path packages, Path templates) {
    public static final List<String> TOP_FOLDERS = List.of("packages", "templates");
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._-]{0,63}");
    private static final int MAX_PATH = 240;
    private static final int MAX_DEPTH = 10;

    /** A content root laid out as {@code <base>/packages} and {@code <base>/templates}. */
    public static ContentRoot under(Path base) {
        return new ContentRoot(base.resolve("packages"), base.resolve("templates"));
    }

    /** The live layout: templates sit beside the packages folder, as the content loader expects. */
    public static ContentRoot live(Path packages) {
        return new ContentRoot(packages, packages.resolveSibling("templates"));
    }

    public static boolean isContentFile(String name) {
        String lower = name.toLowerCase(Locale.ROOT);
        return lower.endsWith(".yml") || lower.endsWith(".yaml") || lower.endsWith(".json");
    }

    /**
     * Checks a logical path from a client.
     *
     * @throws StudioException {@code BAD_REQUEST} naming what is wrong with it
     */
    public static String validate(String logical) throws StudioException {
        if (logical == null || logical.isBlank() || logical.length() > MAX_PATH) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "A file path is required (at most " + MAX_PATH + " characters).");
        }
        String[] segments = logical.split("/", -1);
        if (segments.length < 2 || segments.length > MAX_DEPTH || !TOP_FOLDERS.contains(segments[0])) {
            throw new StudioException(StudioException.Status.BAD_REQUEST,
                    "Paths start with packages/ or templates/ and are at most " + MAX_DEPTH + " folders deep.");
        }
        for (int index = 1; index < segments.length; index++) {
            if (!SEGMENT.matcher(segments[index]).matches() || segments[index].contains("..")) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, "'" + segments[index]
                        + "' is not a valid name: use letters, digits, '.', '_' and '-', starting with a letter or digit.");
            }
        }
        if (!isContentFile(segments[segments.length - 1])) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "Content files end in .yml, .yaml or .json.");
        }
        return logical;
    }

    /** The file a validated logical path names; refuses anything that would leave the root. */
    public Path resolve(String logical) throws StudioException {
        validate(logical);
        int slash = logical.indexOf('/');
        Path top = logical.startsWith("packages/") ? packages : templates;
        Path file = top.resolve(logical.substring(slash + 1)).normalize();
        if (!file.startsWith(top.normalize())) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "That path leaves the content folder.");
        }
        return file;
    }

    /** Every content file, by logical path, with its {@link #hash}. */
    public Map<String, String> hashes() throws IOException {
        Map<String, String> hashes = new TreeMap<>();
        collect("packages", packages, hashes);
        collect("templates", templates, hashes);
        return hashes;
    }

    private static void collect(String prefix, Path top, Map<String, String> into) throws IOException {
        if (!Files.isDirectory(top)) {
            return;
        }
        try (Stream<Path> files = Files.walk(top)) {
            for (Path file : (Iterable<Path>) files::iterator) {
                if (!Files.isRegularFile(file) || !isContentFile(file.getFileName().toString()) || hidden(top, file)) {
                    continue;
                }
                String logical = prefix + "/" + top.relativize(file).toString().replace('\\', '/');
                try {
                    validate(logical);
                } catch (StudioException unaddressable) {
                    continue;
                }
                into.put(logical, hash(Files.readAllBytes(file)));
            }
        } catch (UncheckedIOException failure) {
            throw failure.getCause();
        }
    }

    private static boolean hidden(Path top, Path file) {
        for (Path part : top.relativize(file)) {
            if (part.toString().startsWith(".")) {
                return true;
            }
        }
        return false;
    }

    /**
     * Makes {@code target}'s content files exactly this root's: writes what differs, removes what
     * this root does not have, and leaves non-content files alone.
     */
    public void mirrorTo(ContentRoot target) throws IOException {
        Map<String, String> mine = hashes();
        Map<String, String> theirs = target.hashes();
        for (Map.Entry<String, String> entry : mine.entrySet()) {
            if (!entry.getValue().equals(theirs.get(entry.getKey()))) {
                writeAtomically(target.file(entry.getKey()), Files.readAllBytes(file(entry.getKey())));
            }
        }
        for (String removed : theirs.keySet()) {
            if (!mine.containsKey(removed)) {
                target.remove(removed);
            }
        }
    }

    /** Deletes one content file and any folders it leaves empty, up to the top folder. */
    public void remove(String logical) throws IOException {
        Path file = file(logical);
        Files.deleteIfExists(file);
        Path top = logical.startsWith("packages/") ? packages : templates;
        Path folder = file.getParent();
        while (folder != null && !folder.equals(top) && folder.startsWith(top)) {
            try (Stream<Path> left = Files.list(folder)) {
                if (left.findAny().isPresent()) {
                    break;
                }
            }
            Files.delete(folder);
            folder = folder.getParent();
        }
    }

    /** Resolves a path already known to be valid (one this class listed). */
    Path file(String logical) {
        try {
            return resolve(logical);
        } catch (StudioException invalid) {
            throw new IllegalArgumentException(invalid.getMessage());
        }
    }

    public static void writeAtomically(Path file, byte[] bytes) throws IOException {
        Files.createDirectories(file.getParent());
        Path temporary = Files.createTempFile(file.getParent(), ".studio-", ".tmp");
        try {
            Files.write(temporary, bytes);
            try {
                Files.move(temporary, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING);
            } catch (AtomicMoveNotSupportedException unsupported) {
                Files.move(temporary, file, StandardCopyOption.REPLACE_EXISTING);
            }
        } finally {
            Files.deleteIfExists(temporary);
        }
    }

    /** A short content hash, used as the file's version (ETag). */
    public static String hash(byte[] bytes) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes), 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    public static String hash(String text) {
        return hash(text.getBytes(StandardCharsets.UTF_8));
    }
}
