package org.hyzionstudios.mysticquests.command;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;
import java.time.Duration;
import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.content.MigrationReport;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.util.Json;
import org.hyzionstudios.mysticquests.narrative.diagnostic.Diagnostic;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaAsset;
import org.hyzionstudios.mysticquests.narrative.media.MediaKind;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.InputResult;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.PuzzleView;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerActivationService.Decision;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition;
import org.hyzionstudios.mysticquests.integration.triggervolumes.QuestLocations;
import org.hyzionstudios.mysticquests.service.QuestResult;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.List;
import java.util.Locale;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * {@code /mquest narrative ...}: inspect a player's story runtime, and make audited corrections to it
 * (§21).
 *
 * <ul>
 *   <li>{@code sessions <player>} lists the sessions the player takes part in.</li>
 *   <li>{@code puzzle <player> <puzzle> [reset|reroll] [reason...]} shows a puzzle's state or starts a
 *       new round.</li>
 *   <li>{@code trigger <player> <volume> [enable|disable|clear] [session|player|party|global]} shows
 *       which level decides a volume, or sets an override.</li>
 *   <li>{@code validate} lists the warnings from the last content reload.</li>
 * </ul>
 *
 * <p>Reading needs {@code mysticquests.command.admin.debug}. Changing anything needs
 * {@code mysticquests.command.admin.narrative}, and every change is written to the narrative audit
 * trail with actor, target, session, before and after.
 */
final class NarrativeCommand {
    static final String READ_PERMISSION = "mysticquests.command.admin.debug";
    static final String WRITE_PERMISSION = "mysticquests.command.admin.narrative";
    static final List<String> SUBCOMMANDS = List.of("sessions", "puzzle", "trigger", "validate");

    private static final String TEXT = "#EEF3FC";
    private static final String MUTED = "#9AA7BD";
    private static final String GREEN = "#7BE495";
    private static final String ORANGE = "#F5A742";
    private static final String RED = "#F2545B";

    private final MysticQuestsRuntime runtime;

    NarrativeCommand(MysticQuestsRuntime runtime) {
        this.runtime = runtime;
    }

    void execute(CommandContext context, String[] args) {
        if (!permitted(context, READ_PERMISSION)) {
            return;
        }
        if (runtime.narrative() == null) {
            send(context, "The narrative runtime is not running.", RED);
            return;
        }
        NarrativeRuntime narrative = runtime.narrative().runtime();
        if (args.length < 2) {
            send(context, "/mquest narrative <sessions|media|cutscene|checkpoint|story|objective|goto|puzzle|trigger|validate|migrate|stats> ...", MUTED);
            return;
        }
        switch (args[1].toLowerCase(Locale.ROOT)) {
            case "sessions" -> sessions(context, narrative, args);
            case "media" -> media(context, narrative, args);
            case "cutscene" -> cutscene(context, narrative, args);
            case "checkpoint" -> checkpoint(context, narrative, args);
            case "story" -> story(context, narrative, args);
            case "goto" -> teleport(context, narrative, args);
            case "objective" -> objective(context, narrative, args);
            case "puzzle" -> puzzle(context, narrative, args);
            case "trigger" -> trigger(context, narrative, args);
            case "validate" -> validate(context, narrative);
            case "migrate" -> migrate(context, narrative, args);
            case "stats" -> stats(context, narrative, args);
            default -> send(context, "Unknown narrative subcommand '" + args[1] + "'.", ORANGE);
        }
    }

    private void sessions(CommandContext context, NarrativeRuntime narrative, String[] args) {
        UUID player = player(context, args, 2);
        if (player == null) {
            return;
        }
        int shown = 0;
        for (SessionOwner owner : narrative.audiences().owners(player)) {
            for (QuestSession session : narrative.sessions().load(owner)) {
                shown++;
                send(context, session.id() + "  " + session.storyKey() + "  " + session.status(), TEXT);
                send(context, "  owner " + session.owner() + ", content " + session.contentVersion()
                        + (session.contentVersion().equals(session.createdContentVersion()) ? "" : " (created " + session.createdContentVersion() + ")")
                        + ", last server " + session.lastServer()
                        + ", node " + session.currentNode()
                        + ", ledger " + session.ledgerSize()
                        + ", components " + session.componentKeys()
                        + ", checkpoints " + session.checkpoints().size(), MUTED);
            }
        }
        if (shown == 0) {
            send(context, "No story sessions for " + player + ".", MUTED);
        }
    }

    /** What a player is hearing: each media channel, its queue, and which level chose their music. */
    private void media(CommandContext context, NarrativeRuntime narrative, String[] args) {
        UUID player = player(context, args, 2);
        if (player == null) {
            return;
        }
        if (args.length >= 5 && args[3].equalsIgnoreCase("replay")) {
            if (!permitted(context, WRITE_PERMISSION)) {
                return;
            }
            MediaAsset asset = NamespacedId.tryParse(args[4]).map(narrative.media()::asset).orElse(null);
            if (asset == null || asset.kind() == MediaKind.MUSIC) {
                send(context, "'" + args[4] + "' is not a playable media id.", ORANGE);
                return;
            }
            QuestMediaService.PlayReport report = narrative.media().play(List.of(player), asset, MediaSink.Placement.HEAD, true);
            narrative.audit().record(actor(context), "media.replay", player.toString(), null, "", asset.id().toString(), reason(args, 5));
            send(context, report.reached() > 0 ? "Replayed " + asset.id() + "." : "Could not reach " + player + ".", GREEN);
            return;
        }
        for (String line : narrative.media().describe(player)) {
            send(context, line, TEXT);
        }
    }

    /**
     * {@code checkpoint <player>} lists the checkpoints in a player's active sessions;
     * {@code checkpoint <player> rewind <label> [reason]} restores one (§21.1), keeping every
     * permanent effect so nothing is given twice.
     */
    private void checkpoint(CommandContext context, NarrativeRuntime narrative, String[] args) {
        UUID player = player(context, args, 2);
        if (player == null) {
            return;
        }
        if (args.length < 5 || !args[3].equalsIgnoreCase("rewind")) {
            int shown = 0;
            for (SessionOwner owner : narrative.audiences().owners(player)) {
                for (QuestSession session : narrative.sessions().load(owner)) {
                    for (QuestSession.Checkpoint checkpoint : session.checkpoints()) {
                        shown++;
                        send(context, session.id() + "  " + checkpoint.label() + "  (" + checkpoint.at() + ")", TEXT);
                    }
                }
            }
            if (shown == 0) {
                send(context, "No checkpoints for " + player + ".", MUTED);
            }
            return;
        }
        if (!permitted(context, WRITE_PERMISSION)) {
            return;
        }
        String label = args[4];
        runtime.sessionService().runOnWorld(player, (entity, store) -> {
            Optional<String> session = narrative.rewind(player, label);
            if (session.isEmpty()) {
                send(context, "No active session of " + player + " has checkpoint '" + label + "'.", ORANGE);
                return;
            }
            narrative.audit().record(actor(context), "checkpoint.rewind", player.toString(), session.get(), "", label, reason(args, 5));
            send(context, "Rewound " + session.get() + " to '" + label + "'.", GREEN);
        });
    }

    /**
     * {@code cutscene <player>} shows the scene a player is watching; {@code cutscene <player> <id> play}
     * starts one for them, and {@code cutscene <player> skip} finishes theirs even when the scene is not
     * skippable.
     */
    private void cutscene(CommandContext context, NarrativeRuntime narrative, String[] args) {
        UUID player = player(context, args, 2);
        if (player == null) {
            return;
        }
        if (args.length == 3) {
            List<String> lines = narrative.cutscenes().describe(player);
            if (lines.isEmpty()) {
                send(context, "No cutscene is playing for " + player + ".", MUTED);
            }
            lines.forEach(line -> send(context, line, TEXT));
            return;
        }
        if (!permitted(context, WRITE_PERMISSION)) {
            return;
        }
        boolean play = args.length >= 5 && args[4].equalsIgnoreCase("play");
        if (!play && !args[3].equalsIgnoreCase("skip")) {
            send(context, "/mquest narrative cutscene <player> [<cutscene> play | skip]", MUTED);
            return;
        }
        Optional<NamespacedId> id = play ? NamespacedId.tryParse(args[3]) : Optional.empty();
        if (play && id.isEmpty()) {
            send(context, "'" + args[3] + "' is not a namespaced id.", ORANGE);
            return;
        }
        runtime.sessionService().runOnWorld(player, (entity, store) -> send(context, "Cutscene: "
                + (play ? narrative.cutscenes().play(player, id.get(), null) : narrative.cutscenes().skip(player, true)), TEXT));
    }

    /**
     * {@code goto <world:volume>} teleports the sender to a trigger volume content names;
     * {@code goto <player> <puzzle> [n]} to the n-th input active in that player's selection, for
     * checking a randomised puzzle (§21.1 "Teleport to referenced quest location").
     */
    private void teleport(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (!context.isPlayer()) {
            send(context, "Only players can teleport.", ORANGE);
            return;
        }
        if (args.length < 3) {
            send(context, "/mquest narrative goto <world:volume> | goto <player> <puzzle> [n]", MUTED);
            return;
        }
        if (!permitted(context, WRITE_PERMISSION)) {
            return;
        }
        String volume = args[2];
        if (args.length >= 4) {
            UUID player = player(context, args, 2);
            Optional<NamespacedId> puzzleId = NamespacedId.tryParse(args[3]);
            if (player == null || puzzleId.isEmpty()) {
                return;
            }
            PuzzleDefinition definition = narrative.content().puzzles().get(puzzleId.get());
            Optional<PuzzleView> view = narrative.puzzles().view(player, puzzleId.get());
            if (definition == null || view.isEmpty()) {
                send(context, "No state for puzzle " + args[3] + " for " + player + ".", ORANGE);
                return;
            }
            List<String> volumes = view.get().activeInputs().stream()
                    .map(input -> definition.input(input).map(PuzzleDefinition.Input::volume).orElse(null))
                    .filter(Objects::nonNull)
                    .toList();
            int index;
            try {
                index = args.length >= 5 ? Integer.parseInt(args[4]) - 1 : 0;
            } catch (NumberFormatException invalid) {
                index = -1;
            }
            if (index < 0 || index >= volumes.size()) {
                send(context, player + " has " + volumes.size() + " active input volume(s) on " + args[3] + ": " + volumes, ORANGE);
                return;
            }
            volume = volumes.get(index);
        }
        QuestLocations.teleportToVolume(runtime.sessionService(), context.sender().getUuid(), volume,
                message -> send(context, message, TEXT));
    }

    /** {@code story <player> <story> restart [reason]}: abandons the active session so the story starts over. */
    private void story(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (args.length < 5 || !args[4].equalsIgnoreCase("restart")) {
            send(context, "/mquest narrative story <player> <story> restart [reason]", MUTED);
            return;
        }
        UUID player = player(context, args, 2);
        if (player == null || !permitted(context, WRITE_PERMISSION)) {
            return;
        }
        Optional<String> session = narrative.restartStory(player, args[3]);
        if (session.isEmpty()) {
            send(context, "Story " + args[3] + " is not active for " + player + ".", ORANGE);
            return;
        }
        narrative.audit().record(actor(context), "story.restart", player.toString(), session.get(), "active", "abandoned", reason(args, 5));
        send(context, "Abandoned " + session.get() + "; " + args[3] + " starts over at the next interaction.", GREEN);
    }

    /**
     * {@code objective <player> <quest> <objective> complete|reset|set <n> [reason]}: changes a v1
     * objective's progress. Completing the last objective completes the quest and pays its rewards,
     * so it runs on the player's world thread.
     */
    private void objective(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (args.length < 6) {
            send(context, "/mquest narrative objective <player> <quest> <objective> complete|reset|set <n> [reason]", MUTED);
            return;
        }
        UUID player = player(context, args, 2);
        if (player == null || !permitted(context, WRITE_PERMISSION)) {
            return;
        }
        String mode = args[5].toLowerCase(Locale.ROOT);
        int value;
        int reasonFrom = 6;
        switch (mode) {
            case "complete" -> value = Integer.MAX_VALUE;
            case "reset" -> value = 0;
            case "set" -> {
                try {
                    value = Integer.parseInt(args.length > 6 ? args[6] : "");
                } catch (NumberFormatException invalid) {
                    send(context, "set needs a number.", ORANGE);
                    return;
                }
                reasonFrom = 7;
            }
            default -> {
                send(context, "Use complete, reset or set <n>.", ORANGE);
                return;
            }
        }
        int target = value;
        String reason = reason(args, reasonFrom);
        runtime.sessionService().runOnWorld(player, (entity, store) -> {
            QuestResult result = runtime.questService().setObjectiveProgress(player, args[3], args[4], target);
            if (result.success()) {
                narrative.audit().record(actor(context), "objective." + mode, player.toString(), null, "",
                        args[3] + "/" + args[4], reason);
            }
            send(context, result.message(), result.success() ? GREEN : ORANGE);
        });
    }

    private void puzzle(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (args.length < 4) {
            send(context, "/mquest narrative puzzle <player> <puzzle> [reset|reroll] [reason...]", MUTED);
            return;
        }
        UUID player = player(context, args, 2);
        Optional<NamespacedId> puzzle = NamespacedId.tryParse(args[3]);
        if (player == null) {
            return;
        }
        if (puzzle.isEmpty() || !narrative.content().puzzles().containsKey(puzzle.get())) {
            send(context, "Unknown puzzle '" + args[3] + "'. Loaded: " + narrative.content().puzzles().keySet(), ORANGE);
            return;
        }
        Optional<PuzzleView> before = narrative.puzzles().view(player, puzzle.get());
        if (args.length < 5) {
            if (before.isEmpty()) {
                send(context, "No state yet: the player's audience has not touched " + puzzle.get() + ".", MUTED);
                return;
            }
            PuzzleView view = before.get();
            send(context, view.puzzle() + " in " + view.sessionId() + " (" + view.owner() + "), round " + view.generation(), TEXT);
            send(context, "  active " + view.activeInputs() + ", activated " + view.activated().keySet()
                    + (view.sequenceProgress().isEmpty() ? "" : ", sequence " + view.sequenceProgress())
                    + (view.machineState() == null ? "" : ", state " + view.machineState())
                    + ", solved " + view.completed() + ", outputs " + (view.outputsComplete() ? "done" : view.completed() ? "PENDING" : "-")
                    + ", seed " + view.seed(), MUTED);
            return;
        }
        String mode = args[4].toLowerCase(Locale.ROOT);
        if (!mode.equals("reset") && !mode.equals("reroll")) {
            send(context, "Use reset or reroll.", ORANGE);
            return;
        }
        if (!permitted(context, WRITE_PERMISSION)) {
            return;
        }
        String actor = actor(context);
        InputResult result = narrative.puzzles().reset(player, puzzle.get(), mode.equals("reroll"), "command:" + actor);
        Optional<PuzzleView> after = narrative.puzzles().view(player, puzzle.get());
        narrative.audit().record(actor, "puzzle." + mode, player.toString(), result.sessionId(),
                before.map(this::summary).orElse("none"), after.map(this::summary).orElse("none"), reason(args, 5));
        send(context, result.message(), GREEN);
    }

    private void trigger(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (args.length < 4) {
            send(context, "/mquest narrative trigger <player> <world:volume> [enable|disable|clear] [session|player|party|global]", MUTED);
            return;
        }
        UUID player = player(context, args, 2);
        if (player == null) {
            return;
        }
        String volume = args[3];
        Decision before = narrative.activation().decide(volume, player);
        if (args.length < 5) {
            send(context, volume + " is " + (before.enabled() ? "enabled" : "DISABLED") + " for " + player
                    + (before.decidedBy() == null ? " (default)" : " by " + before.decidedBy() + " override on " + before.owner()), TEXT);
            return;
        }
        if (!permitted(context, WRITE_PERMISSION)) {
            return;
        }
        Boolean enabled = switch (args[4].toLowerCase(Locale.ROOT)) {
            case "enable" -> Boolean.TRUE;
            case "disable" -> Boolean.FALSE;
            default -> null;
        };
        TriggerScope scope = TriggerScope.parse(args.length >= 6 ? args[5] : "player");
        if (scope == null) {
            send(context, "Unknown scope; use session, player, party or global.", ORANGE);
            return;
        }
        ScopeContext base = ScopeContext.player(player).withParty(narrative.audiences().party(player).orElse(null));
        List<String> sessionIds = scope != TriggerScope.STORY_SESSION
                ? List.of("")
                : narrative.audiences().owners(player).stream()
                        .flatMap(owner -> narrative.sessions().load(owner).stream())
                        .filter(QuestSession::active)
                        .map(QuestSession::id)
                        .toList();
        if (sessionIds.isEmpty()) {
            send(context, "The player has no active story session to change.", ORANGE);
            return;
        }
        for (String sessionId : sessionIds) {
            ScopeContext target = sessionId.isEmpty() ? base : base.withSession(sessionId, null);
            StateResult result = narrative.activation().set(scope, volume, enabled, target);
            if (result.rejected()) {
                send(context, result.message(), RED);
                return;
            }
        }
        Decision after = narrative.activation().decide(volume, player);
        narrative.audit().record(actor(context), "trigger." + args[4].toLowerCase(Locale.ROOT), player.toString(),
                null, describe(before), describe(after) + " via " + scope + " " + volume, reason(args, 6));
        send(context, volume + " is now " + (after.enabled() ? "enabled" : "disabled") + " for " + player + ".", GREEN);
    }

    /**
     * {@code stats [reset]} (§28, §29): sizes against their limits, failure and recovery counters, and
     * the slowest operations since start or the last reset.
     */
    private void stats(CommandContext context, NarrativeRuntime narrative, String[] args) {
        if (args.length > 2 && args[2].equalsIgnoreCase("reset")) {
            if (!permitted(context, WRITE_PERMISSION)) {
                return;
            }
            narrative.metrics().reset();
            send(context, "Narrative counters and timings reset.", GREEN);
            return;
        }
        NarrativeMetrics.Snapshot snapshot = narrative.metricsSnapshot();
        Duration window = Duration.between(snapshot.since(), snapshot.at());
        send(context, "Narrative stats for the last " + humanDuration(window) + ":", TEXT);
        for (NarrativeMetrics.Gauge gauge : snapshot.gauges()) {
            send(context, "  " + gauge.name() + ": " + gauge.value() + (gauge.limit() > 0 ? " / " + gauge.limit() : ""),
                    gauge.over() ? RED : MUTED);
        }
        List<String> counts = new ArrayList<>();
        snapshot.counters().forEach((counter, value) -> {
            if (value > 0) {
                counts.add(counter.name().toLowerCase(Locale.ROOT).replace('_', ' ') + " " + value);
            }
        });
        send(context, counts.isEmpty() ? "  No narrative activity yet." : "  " + String.join(", ", counts), TEXT);
        boolean troubled = snapshot.count(NarrativeMetrics.Counter.ACTIONS_FAILED) > 0
                || snapshot.count(NarrativeMetrics.Counter.MISSING_REFERENCES) > 0
                || snapshot.count(NarrativeMetrics.Counter.CONDITION_FAULTS) > 0
                || snapshot.count(NarrativeMetrics.Counter.LIMITS_REACHED) > 0;
        if (troubled) {
            send(context, "  Failures and limits are explained line by line in the server log.", ORANGE);
        }
        List<NarrativeMetrics.Timing> slowest = snapshot.slowest(6);
        if (!slowest.isEmpty()) {
            send(context, "Slowest operations (count, mean, worst):", TEXT);
            slowest.forEach(timing -> send(context, "  " + timing.operation() + "  x" + timing.count() + ", "
                    + millis(timing.meanNanos()) + ", " + millis(timing.maxNanos()),
                    timing.maxNanos() > NarrativeMetrics.SLOW_NANOS ? ORANGE : MUTED));
        }
    }

    private static String millis(long nanos) {
        return String.format(Locale.ROOT, "%.2fms", nanos / 1_000_000.0);
    }

    private static String humanDuration(Duration duration) {
        long minutes = duration.toMinutes();
        if (minutes < 1) {
            return duration.toSeconds() + "s";
        }
        return minutes < 120 ? minutes + "m" : duration.toHours() + "h " + duration.toMinutesPart() + "m";
    }

    /**
     * {@code migrate [export]} (§25): how existing content and stored state fare under 2.0. Lists
     * v1 patterns with a safer 2.0 replacement, unreadable content files, and stored documents that
     * could not be read (quarantined, never overwritten). {@code export} writes the full report to
     * {@code reports/} in the data directory.
     */
    private void migrate(CommandContext context, NarrativeRuntime narrative, String[] args) {
        MigrationReport report;
        try {
            report = MigrationReport.scan(runtime.dataDirectory().resolve(runtime.config().packagesPath()),
                    runtime.mapper(), Json.createYamlMapper());
        } catch (IOException failure) {
            send(context, "Could not scan packages: " + failure.getMessage(), RED);
            return;
        }
        List<String> quarantined = new ArrayList<>(narrative.sessions().quarantined());
        narrative.store().quarantined().forEach(owner -> quarantined.add("state of " + owner));
        int unchanged = report.unchanged().values().stream().mapToInt(Integer::intValue).sum();
        send(context, "Migration report: " + unchanged + " v1 entries run unchanged in " + report.unchanged().size()
                + " package(s); " + report.items(MigrationReport.Kind.UPGRADE).size() + " can upgrade; "
                + (report.items(MigrationReport.Kind.MANUAL).size() + quarantined.size()) + " need manual action.", TEXT);
        report.items(MigrationReport.Kind.MANUAL).forEach(item ->
                send(context, "MANUAL " + item.file() + ": " + item.advice(), RED));
        quarantined.forEach(document -> send(context, "MANUAL " + document
                + " is unreadable or from a newer release; it is never overwritten. Restore it from a backup or upgrade.", RED));
        report.items(MigrationReport.Kind.UPGRADE).stream().limit(10).forEach(item ->
                send(context, "UPGRADE " + item.file() + " " + item.path() + " (" + item.type() + "): " + item.advice(), ORANGE));
        if (report.items(MigrationReport.Kind.UPGRADE).size() > 10) {
            send(context, "... and " + (report.items(MigrationReport.Kind.UPGRADE).size() - 10) + " more; use export for all.", MUTED);
        }
        if (args.length < 3 || !args[2].equalsIgnoreCase("export")) {
            return;
        }
        try {
            Path directory = runtime.dataDirectory().resolve("reports");
            Files.createDirectories(directory);
            Path file = directory.resolve("migration-" + System.currentTimeMillis() + ".json");
            Map<String, Object> document = new LinkedHashMap<>(report.toMap());
            document.put("quarantined", quarantined);
            runtime.mapper().writerWithDefaultPrettyPrinter().writeValue(file.toFile(), document);
            send(context, "Exported to " + runtime.dataDirectory().relativize(file), GREEN);
        } catch (IOException failure) {
            send(context, "Export failed: " + failure.getMessage(), RED);
        }
    }

    private void validate(CommandContext context, NarrativeRuntime narrative) {
        List<Diagnostic> warnings = narrative.content().report().warnings();
        send(context, narrative.content().puzzles().size() + " puzzles, " + narrative.content().schemas().variables().size()
                + " variables, " + narrative.content().schemas().tags().size() + " tags; " + warnings.size() + " warnings.", TEXT);
        warnings.stream().limit(20).forEach(warning -> send(context, warning.toString(), ORANGE));
        if (warnings.size() > 20) {
            send(context, "... " + (warnings.size() - 20) + " more in the server log.", MUTED);
        }
    }

    private String summary(PuzzleView view) {
        return "round " + view.generation() + ", activated " + view.activated().size() + "/" + view.activeInputs().size()
                + ", solved " + view.completed();
    }

    private static String describe(Decision decision) {
        return (decision.enabled() ? "enabled" : "disabled") + (decision.decidedBy() == null ? "" : " by " + decision.decidedBy());
    }

    private static String reason(String[] args, int from) {
        return args.length > from ? String.join(" ", Arrays.copyOfRange(args, from, args.length)) : null;
    }

    private UUID player(CommandContext context, String[] args, int index) {
        String token = args.length > index ? args[index] : "self";
        if (token.equalsIgnoreCase("self")) {
            return context.sender().getUuid();
        }
        Optional<UUID> online = runtime.resolveOnlinePlayer(token);
        if (online.isPresent()) {
            return online.get();
        }
        try {
            return UUID.fromString(token);
        } catch (IllegalArgumentException invalid) {
            send(context, "Unknown player: " + token, RED);
            return null;
        }
    }

    private static String actor(CommandContext context) {
        return context.isPlayer() ? context.sender().getUuid().toString() : "console";
    }

    private boolean permitted(CommandContext context, String permission) {
        if (context.sender().hasPermission("mysticquests.admin") || context.sender().hasPermission(permission)) {
            return true;
        }
        send(context, "Missing permission: " + permission, RED);
        return false;
    }

    private static void send(CommandContext context, String text, String color) {
        context.sendMessage(Message.raw(text).color(color));
    }
}
