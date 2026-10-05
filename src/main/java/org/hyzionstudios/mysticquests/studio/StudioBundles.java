package org.hyzionstudios.mysticquests.studio;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Map;
import java.util.TreeMap;
import java.util.zip.ZipEntry;
import java.util.zip.ZipException;
import java.util.zip.ZipInputStream;
import java.util.zip.ZipOutputStream;

/**
 * Moving a release between servers (§23: separate development, staging and production targets).
 * A release downloads as a zip of its content files; importing one on another server loads it into
 * that server's draft, where it is validated and published like any other change. Promotion is never
 * a direct write to another server's live content.
 *
 * <p>An import is checked entry by entry: only content files at valid paths are accepted (no
 * {@code ..}, no absolute paths, no other file types), with limits on the number of files and their
 * total size, so a crafted zip cannot write outside the draft or exhaust the disk.
 */
public final class StudioBundles {
    static final int MAX_FILES = 2000;
    static final long MAX_TOTAL = 32L * 1024 * 1024;

    private StudioBundles() {
    }

    /** The content of {@code root} as a zip, with logical paths ({@code packages/...}) as entry names. */
    public static byte[] export(ContentRoot root) throws IOException {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (ZipOutputStream zip = new ZipOutputStream(out)) {
            for (String path : root.hashes().keySet()) {
                zip.putNextEntry(new ZipEntry(path));
                zip.write(Files.readAllBytes(root.file(path)));
                zip.closeEntry();
            }
        }
        return out.toByteArray();
    }

    /**
     * Reads a bundle into memory, checking every entry first.
     *
     * @return content files by logical path
     * @throws StudioException {@code BAD_REQUEST} naming the first unacceptable entry, or {@code TOO_LARGE}
     */
    public static Map<String, byte[]> read(byte[] bundle) throws IOException, StudioException {
        Map<String, byte[]> files = new TreeMap<>();
        long total = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(bundle))) {
            ZipEntry entry;
            while ((entry = zip.getNextEntry()) != null) {
                if (entry.isDirectory()) {
                    continue;
                }
                String name = entry.getName().replace('\\', '/');
                ContentRoot.validate(name);
                if (files.size() >= MAX_FILES) {
                    throw new StudioException(StudioException.Status.TOO_LARGE, "A bundle holds at most " + MAX_FILES + " files.");
                }
                ByteArrayOutputStream content = new ByteArrayOutputStream();
                byte[] chunk = new byte[8192];
                int read;
                while ((read = zip.read(chunk)) != -1) {
                    total += read;
                    if (total > MAX_TOTAL || content.size() + read > StudioWorkspace.MAX_FILE_BYTES) {
                        throw new StudioException(StudioException.Status.TOO_LARGE, "The bundle, or " + name + " in it, is too large.");
                    }
                    content.write(chunk, 0, read);
                }
                if (files.put(name, content.toByteArray()) != null) {
                    throw new StudioException(StudioException.Status.BAD_REQUEST, name + " is in the bundle twice.");
                }
            }
        } catch (ZipException corrupt) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "That is not a readable release bundle: " + corrupt.getMessage());
        }
        if (files.isEmpty()) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The bundle holds no content files.");
        }
        return files;
    }

    /** Writes checked bundle files as a content root under {@code folder}, which must be empty or absent. */
    public static ContentRoot unpack(Map<String, byte[]> files, Path folder) throws IOException, StudioException {
        StudioWorkspace.deleteTree(folder);
        ContentRoot root = ContentRoot.under(folder);
        Files.createDirectories(root.packages());
        for (Map.Entry<String, byte[]> file : files.entrySet()) {
            ContentRoot.writeAtomically(root.resolve(file.getKey()), file.getValue());
        }
        return root;
    }
}
