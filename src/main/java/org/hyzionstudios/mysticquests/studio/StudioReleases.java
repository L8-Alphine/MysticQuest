package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.studio.StudioWorkspace.Change;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
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
import java.util.Map;

/**
 * Publishing and release history (§12 "Version History", §23).
 *
 * <p>Publishing is validated, all-or-nothing and recorded: the draft must validate, it is
 * snapshotted as release <i>n</i>, copied over the live content and reloaded; if the server refuses
 * the reload, the previous live content is put back and reloaded, and nothing is recorded. The first
 * publish also snapshots the content as it was before the Studio as release 0, so every state the
 * server has run since can be restored.
 *
 * <p>History is append-only. Rolling back loads an old release into the draft, and publishing it
 * makes a new release, so the record of what ran when is never rewritten.
 */
public final class StudioReleases {
    public record Release(int number, Instant at, String actor, String message, boolean baseline, List<Change> changes) {
        public Release {
            changes = List.copyOf(changes);
        }
    }

    /** Reloads the live content; throws with the server's reasons when it refuses. */
    @FunctionalInterface
    public interface Reloader {
        void reload() throws IOException;
    }

    private final Path root;
    private final Path historyFile;
    private final StudioWorkspace workspace;
    private final StudioValidation.Validator validator;
    private final Reloader reloader;
    private final Clock clock;
    private final ObjectMapper json;

    public StudioReleases(Path studioRoot, StudioWorkspace workspace, StudioValidation.Validator validator, Reloader reloader,
                          Clock clock, ObjectMapper json) {
        this.root = studioRoot.resolve("releases");
        this.historyFile = studioRoot.resolve("releases.jsonl");
        this.workspace = workspace;
        this.validator = validator;
        this.reloader = reloader;
        this.clock = clock;
        this.json = json;
    }

    /** Every release, oldest first. */
    public synchronized List<Release> history() throws IOException {
        List<Release> releases = new ArrayList<>();
        if (!Files.isRegularFile(historyFile)) {
            return releases;
        }
        for (String line : Files.readAllLines(historyFile, StandardCharsets.UTF_8)) {
            if (line.isBlank()) {
                continue;
            }
            try {
                JsonNode node = json.readTree(line);
                List<Change> changes = new ArrayList<>();
                node.path("changes").forEach(change -> changes.add(new Change(change.path("path").asText(),
                        Change.Kind.valueOf(change.path("kind").asText()))));
                releases.add(new Release(node.path("number").asInt(), Instant.parse(node.path("at").asText()),
                        node.path("actor").asText(), node.path("message").asText(), node.path("baseline").asBoolean(), changes));
            } catch (IOException | RuntimeException unreadable) {
                // A torn last line from a crash; the snapshot folders are still there.
            }
        }
        return releases;
    }

    /**
     * Publishes the draft.
     *
     * @param overwriteLiveEdits replace live files that were edited outside the Studio since the
     *         draft was made; without it, such edits stop the publish so they are not lost silently
     * @throws StudioException {@code BAD_REQUEST} when there is nothing to publish, {@code CONFLICT}
     *         when live files changed outside the Studio, the draft has errors (detail: the
     *         {@link StudioValidation}), or the server refused the reload
     */
    public synchronized Release publish(String actor, String message, boolean overwriteLiveEdits) throws IOException, StudioException {
        List<Change> changes = workspace.changes();
        if (changes.isEmpty()) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The draft matches the live content; there is nothing to publish.");
        }
        List<Change> drift = workspace.liveDrift();
        if (!drift.isEmpty() && !overwriteLiveEdits) {
            throw new StudioException(StudioException.Status.CONFLICT, drift.size() + " live file(s) were edited outside the Studio"
                    + " since this draft was made. Reset the draft to start from them, or publish with overwrite to replace them.", drift);
        }
        StudioValidation validation = validator.validate(workspace.draft());
        if (!validation.ok()) {
            throw new StudioException(StudioException.Status.CONFLICT,
                    "The draft has " + validation.errors() + " error(s); fix them before publishing.", validation);
        }

        List<Release> history = history();
        if (history.isEmpty()) {
            Path baseline = snapshotFolder(0);
            WorkspaceCopy.replace(workspace.live(), ContentRoot.under(baseline));
            append(new Release(0, clock.instant(), "server", "Content before the Studio's first release", true, List.of()));
        }
        int number = history.isEmpty() ? 1 : history.getLast().number() + 1;
        ContentRoot snapshot = ContentRoot.under(snapshotFolder(number));
        WorkspaceCopy.replace(workspace.draft(), snapshot);
        ContentRoot backup = ContentRoot.under(root.resolve("pending-backup"));
        WorkspaceCopy.replace(workspace.live(), backup);
        try {
            workspace.draft().mirrorTo(workspace.live());
            reloader.reload();
        } catch (IOException refused) {
            backup.mirrorTo(workspace.live());
            try {
                reloader.reload();
            } catch (IOException ignored) {
                // The previous content was running before; restoring its files is what matters.
            }
            StudioWorkspace.deleteTree(snapshotFolder(number));
            throw new StudioException(StudioException.Status.CONFLICT,
                    "The server refused the release and kept the previous content: " + refused.getMessage());
        } finally {
            StudioWorkspace.deleteTree(root.resolve("pending-backup"));
        }
        workspace.rebase();
        Release release = new Release(number, clock.instant(), actor, message == null ? "" : message.strip(), false, changes);
        append(release);
        return release;
    }

    /** Loads a past release into the draft, ready to review and publish again. */
    public synchronized void restore(int number) throws IOException, StudioException {
        Path folder = snapshotFolder(number);
        if (number < 0 || !Files.isDirectory(folder)) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "There is no release " + number + ".");
        }
        workspace.loadFrom(ContentRoot.under(folder));
    }

    /** A release as a zip, for importing on another server (§23 promotion). */
    public synchronized byte[] export(int number) throws IOException, StudioException {
        return StudioBundles.export(snapshot(number));
    }

    /**
     * Loads a release bundle from another server into the draft, replacing the draft's content, so
     * it is reviewed, validated and published here like any other change.
     *
     * @return how many files the bundle held
     */
    public synchronized int importBundle(byte[] bundle) throws IOException, StudioException {
        java.util.Map<String, byte[]> files = StudioBundles.read(bundle);
        Path staging = root.resolve("import-staging");
        try {
            workspace.loadFrom(StudioBundles.unpack(files, staging));
        } finally {
            StudioWorkspace.deleteTree(staging);
        }
        return files.size();
    }

    /** What a release changed, file by file, compared with the release before it. */
    public synchronized List<Change> compare(int from, int to) throws IOException, StudioException {
        Map<String, String> before = snapshot(from).hashes();
        Map<String, String> after = snapshot(to).hashes();
        return StudioWorkspace.diff(before, after);
    }

    public ContentRoot snapshot(int number) throws StudioException {
        Path folder = snapshotFolder(number);
        if (number < 0 || !Files.isDirectory(folder)) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "There is no release " + number + ".");
        }
        return ContentRoot.under(folder);
    }

    private Path snapshotFolder(int number) {
        return root.resolve(String.format("%05d", number));
    }

    private void append(Release release) throws IOException {
        ObjectNode line = json.createObjectNode();
        line.put("number", release.number());
        line.put("at", release.at().toString());
        line.put("actor", release.actor());
        line.put("message", release.message());
        line.put("baseline", release.baseline());
        ArrayNode changes = line.putArray("changes");
        for (Change change : release.changes()) {
            changes.addObject().put("path", change.path()).put("kind", change.kind().name());
        }
        Files.createDirectories(historyFile.getParent());
        Files.writeString(historyFile, json.writeValueAsString(line) + "\n", StandardCharsets.UTF_8,
                StandardOpenOption.CREATE, StandardOpenOption.APPEND);
    }

    /** Copies one content root over another folder that may not exist yet. */
    static final class WorkspaceCopy {
        private WorkspaceCopy() {
        }

        static void replace(ContentRoot from, ContentRoot to) throws IOException {
            Files.createDirectories(to.packages());
            from.mirrorTo(to);
        }
    }
}
