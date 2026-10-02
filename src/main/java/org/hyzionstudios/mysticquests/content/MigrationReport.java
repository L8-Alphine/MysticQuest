package org.hyzionstudios.mysticquests.content;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Stream;

/**
 * §25 migration report: what existing content does under 2.0, item by item.
 *
 * <p>v1 content needs no rewrite: the v1 loader rejects unsupported types up front, so whatever
 * loads runs unchanged, and narrative content reaches it through the compatibility bridge. The
 * report therefore sorts content into three groups:
 * <ul>
 *   <li><b>unchanged</b>: v1 events, conditions and objectives that run as they always did
 *       (counted per package, not listed);</li>
 *   <li><b>upgrade</b>: v1 patterns a 2.0 feature now does more safely, with the replacement;</li>
 *   <li><b>manual</b>: files that cannot be read, which nothing can convert automatically.</li>
 * </ul>
 * It reads files only and changes nothing. Player state is not converted: v1 content still writes
 * the v1 store, so a copy would drift from it (see docs/2.0/gap-analysis.md §3, row 25).
 */
public final class MigrationReport {
    public enum Kind { UPGRADE, MANUAL }

    public record Item(Kind kind, String packageId, String file, String path, String type, String advice) {
    }

    /** v1 types with a 2.0 replacement, and why the replacement is better. */
    private static final Map<String, String> UPGRADES = Map.ofEntries(
            Map.entry("spawnNpc", "mysticquests:entity.spawn claims the NPC for the story session as it spawns, so unrelated "
                    + "players never see, target, hit or use it"),
            Map.entry("despawnNpc", "mysticquests:entity.despawn also releases the story claim"),
            Map.entry("hidePlayer", "still supported; for story isolation, story entities and staff bypass handle sight per viewer"),
            Map.entry("hideEntity", "still supported; mysticquests:entity.claim isolates sight, targeting, damage and use together"),
            Map.entry("showEntity", "still supported; mysticquests:entity.release returns a claimed entity to the world"),
            Map.entry("preventTargeting", "still supported; mysticquests:entity.claim also blocks damage and use"),
            Map.entry("setCamera", "a cutscene with setCamera in onEnd restores the camera even when the scene is skipped or "
                    + "the player disconnects"));

    private final List<Item> items = new ArrayList<>();
    private final Map<String, Integer> unchanged = new TreeMap<>();
    private final Map<String, Integer> upgraded = new TreeMap<>();

    private MigrationReport() {
    }

    /**
     * Scans every content file under {@code packagesRoot}. Narrative sections are skipped: they are
     * already 2.0 content, and a {@code setCamera} inside a cutscene is the recommended form.
     */
    public static MigrationReport scan(Path packagesRoot, ObjectMapper json, ObjectMapper yaml) throws IOException {
        MigrationReport report = new MigrationReport();
        if (!Files.isDirectory(packagesRoot)) {
            return report;
        }
        List<Path> files;
        try (Stream<Path> walk = Files.walk(packagesRoot)) {
            files = walk.filter(Files::isRegularFile).filter(MigrationReport::isContentFile).sorted().toList();
        }
        for (Path file : files) {
            Path parent = file.getParent();
            String packageId = parent.equals(packagesRoot) ? "" : packagesRoot.relativize(parent).toString().replace('\\', '/');
            String name = packagesRoot.relativize(file).toString().replace('\\', '/');
            JsonNode document;
            try {
                String lower = name.toLowerCase(Locale.ROOT);
                document = (lower.endsWith(".yml") || lower.endsWith(".yaml") ? yaml : json).readTree(file.toFile());
            } catch (IOException unreadable) {
                report.items.add(new Item(Kind.MANUAL, packageId, name, "", "", "cannot be parsed: " + unreadable.getMessage()));
                continue;
            }
            report.walk(document, packageId, name, "");
        }
        return report;
    }

    private void walk(JsonNode node, String packageId, String file, String path) {
        if (node == null) {
            return;
        }
        if (node.isArray()) {
            for (int index = 0; index < node.size(); index++) {
                walk(node.get(index), packageId, file, path + "[" + index + "]");
            }
            return;
        }
        if (!node.isObject()) {
            return;
        }
        JsonNode type = node.get("type");
        if (type != null && type.isTextual() && !type.asText().contains(":")) {
            String advice = UPGRADES.get(type.asText());
            if (advice != null) {
                items.add(new Item(Kind.UPGRADE, packageId, file, path, type.asText(), advice));
                upgraded.merge(packageId, 1, Integer::sum);
            } else {
                unchanged.merge(packageId, 1, Integer::sum);
            }
        }
        node.properties().forEach(entry -> {
            if (path.isEmpty() && LoadedContent.NARRATIVE_SECTIONS.contains(entry.getKey())) {
                return;
            }
            walk(entry.getValue(), packageId, file, path.isEmpty() ? entry.getKey() : path + "." + entry.getKey());
        });
    }

    private static boolean isContentFile(Path file) {
        String name = file.getFileName().toString().toLowerCase(Locale.ROOT);
        return (name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml"))
                && !name.contains(".mq-staging") && !name.contains(".mq-rollback");
    }

    public List<Item> items() {
        return List.copyOf(items);
    }

    public List<Item> items(Kind kind) {
        return items.stream().filter(item -> item.kind() == kind).toList();
    }

    /** Typed v1 entries (events, conditions, objectives) that run unchanged, per package. */
    public Map<String, Integer> unchanged() {
        return Map.copyOf(unchanged);
    }

    /** A JSON-ready summary for export. */
    public Map<String, Object> toMap() {
        Map<String, Object> map = new LinkedHashMap<>();
        map.put("unchangedPerPackage", unchanged);
        map.put("upgradesPerPackage", upgraded);
        map.put("items", items);
        return map;
    }
}
