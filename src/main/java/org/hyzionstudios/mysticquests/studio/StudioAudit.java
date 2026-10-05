package org.hyzionstudios.mysticquests.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.function.Consumer;

/**
 * Append-only record of everything done through the Studio (§23): one JSON line per sign-in, edit,
 * publish, restore and refused request, never rewritten. Also sent to the server log.
 */
public final class StudioAudit {
    public record Entry(Instant at, String actor, String action, String target, String detail) {
    }

    private final Path file;
    private final Clock clock;
    private final ObjectMapper json;
    private final Consumer<String> log;

    public StudioAudit(Path file, Clock clock, ObjectMapper json, Consumer<String> log) {
        this.file = file;
        this.clock = clock;
        this.json = json;
        this.log = log;
    }

    public synchronized void record(String actor, String action, String target, String detail) {
        Entry entry = new Entry(clock.instant(), actor, action, target == null ? "" : target, detail == null ? "" : detail);
        ObjectNode line = json.createObjectNode();
        line.put("at", entry.at().toString());
        line.put("actor", entry.actor());
        line.put("action", entry.action());
        line.put("target", entry.target());
        line.put("detail", entry.detail());
        try {
            Files.createDirectories(file.getParent());
            Files.writeString(file, json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                    StandardOpenOption.CREATE, StandardOpenOption.APPEND);
        } catch (IOException failure) {
            log.accept("Studio audit could not be written (" + failure.getMessage() + "): " + line);
            return;
        }
        log.accept("Studio: " + entry.actor() + " " + entry.action() + " " + entry.target()
                + (entry.detail().isEmpty() ? "" : " (" + entry.detail() + ")"));
    }

    /** The newest {@code limit} entries, newest first. */
    public synchronized List<Entry> recent(int limit) throws IOException {
        List<Entry> entries = new ArrayList<>();
        if (!Files.isRegularFile(file)) {
            return entries;
        }
        List<String> lines = Files.readAllLines(file, StandardCharsets.UTF_8);
        for (int index = lines.size() - 1; index >= 0 && entries.size() < limit; index--) {
            String line = lines.get(index);
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = json.readTree(line);
                entries.add(new Entry(Instant.parse(node.path("at").asText()), node.path("actor").asText(),
                        node.path("action").asText(), node.path("target").asText(), node.path("detail").asText()));
            } catch (IOException | RuntimeException unreadable) {
                // A torn last line from a crash is skipped rather than hiding everything before it.
            }
        }
        return entries;
    }
}
