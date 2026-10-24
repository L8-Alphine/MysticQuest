package org.hyzionstudios.mysticquests.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.io.InputStream;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Quest packages MysticQuests ships inside its jar and writes into the packages directory once, the
 * first time it starts, so a new server has Hytale's own story to play and to edit.
 *
 * <p>Once written, a package belongs to the server: it is never overwritten, and one the owner
 * deletes does not come back. What was installed is recorded in {@code data/bundled-packages.json};
 * removing a package's entry there installs it fresh on the next start. A directory of the same name
 * that is already there is taken as the owner's and only recorded.
 */
public final class BundledPackages {
    /** Where bundled packages live in the jar, below which each package is a directory. */
    private static final String RESOURCE_ROOT = "mysticquests/default-packages/";

    /**
     * Every bundled package and its files. A jar's directories cannot be listed through the class
     * loader, so the files are named here.
     */
    private static final Map<String, List<String>> PACKAGES = Map.of(
            "hytale_chapter_1", List.of("package.yml", "quests.yml", "README.md"),
            // MysticRPG's six level-10 class advancement trials (ADR-012 point 5 in MysticRPG): the
            // shared trial package, installed where MysticQuests loads packages.
            "mysticrpg_trials", List.of("package.yml", "quests.yml", "README.md"),
            // MysticRPG's Paragon era challenges (eras 2-5) and recovery trial, provisional content
            // pending the product owner's sign-off (MysticRPG PO addendum P-47, P-48).
            "mysticrpg_paragon_trials", List.of("package.yml", "quests.yml", "README.md"));

    private BundledPackages() {
    }

    /** The outcome for one bundled package. */
    public enum Result { INSTALLED, ALREADY_PRESENT, PREVIOUSLY_INSTALLED }

    /**
     * Writes every bundled package that was never installed before.
     *
     * @param packagesDirectory the server's packages directory
     * @param record the installation record, normally {@code data/bundled-packages.json}
     * @return what happened to each bundled package, by id
     */
    public static Map<String, Result> install(Path packagesDirectory, Path record, ObjectMapper mapper) throws IOException {
        ObjectNode installed = readRecord(record, mapper);
        Map<String, Result> results = new LinkedHashMap<>();
        boolean changed = false;
        for (Map.Entry<String, List<String>> bundled : PACKAGES.entrySet()) {
            String id = bundled.getKey();
            if (installed.has(id)) {
                results.put(id, Result.PREVIOUSLY_INSTALLED);
                continue;
            }
            Path target = packagesDirectory.resolve(id);
            if (Files.exists(target)) {
                results.put(id, Result.ALREADY_PRESENT);
            } else {
                Files.createDirectories(packagesDirectory);
                copy(id, bundled.getValue(), target, record.toAbsolutePath().getParent());
                results.put(id, Result.INSTALLED);
            }
            installed.put(id, Instant.now().toString());
            changed = true;
        }
        if (changed) {
            Files.createDirectories(record.toAbsolutePath().getParent());
            mapper.writerWithDefaultPrettyPrinter().writeValue(record.toFile(), installed);
        }
        return results;
    }

    /** Bundled package ids, for diagnostics. */
    public static List<String> ids() {
        return new ArrayList<>(PACKAGES.keySet());
    }

    /**
     * Writes into a staging directory first, so a failure halfway never leaves half a package. It sits
     * beside the record rather than in the packages directory, where a leftover would load as a package.
     */
    private static void copy(String id, List<String> files, Path target, Path stagingRoot) throws IOException {
        Path staging = stagingRoot.resolve(id + ".installing");
        deleteQuietly(staging);
        Files.createDirectories(staging);
        try {
            for (String file : files) {
                String resource = RESOURCE_ROOT + id + "/" + file;
                try (InputStream in = BundledPackages.class.getClassLoader().getResourceAsStream(resource)) {
                    if (in == null) {
                        throw new IOException("Bundled package file is missing from the jar: " + resource);
                    }
                    Files.copy(in, staging.resolve(file), StandardCopyOption.REPLACE_EXISTING);
                }
            }
            Files.move(staging, target);
        } catch (IOException failure) {
            deleteQuietly(staging);
            throw failure;
        }
    }

    private static ObjectNode readRecord(Path record, ObjectMapper mapper) throws IOException {
        if (!Files.isRegularFile(record)) {
            return mapper.createObjectNode();
        }
        JsonNode node = mapper.readTree(record.toFile());
        return node instanceof ObjectNode object ? object : mapper.createObjectNode();
    }

    private static void deleteQuietly(Path directory) {
        if (!Files.exists(directory)) {
            return;
        }
        try (var walk = Files.walk(directory)) {
            walk.sorted(java.util.Comparator.reverseOrder()).forEach(path -> {
                try {
                    Files.deleteIfExists(path);
                } catch (IOException ignored) {
                    // Best effort: a leftover staging directory is retried on the next start.
                }
            });
        } catch (IOException ignored) {
            // Same: nothing depends on the cleanup succeeding.
        }
    }
}
