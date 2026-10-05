package org.hyzionstudios.mysticquests.command;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Part;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import javax.annotation.Nullable;
import java.util.Arrays;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * {@code /mquest player <player> ...}: read and change one player's quest state from chat or the
 * console, through {@link PlayerStateAdmin} like the in-game admin page and the web Studio.
 *
 * <ul>
 *   <li>{@code info} — quests, tags, variables, story state and sessions (the default).</li>
 *   <li>{@code clear <all|progress|part[,part...]> <reason...>} — parts are quests, tags, variables,
 *       story, sessions and preferences; {@code progress} is everything but preferences.</li>
 *   <li>{@code quest <start|complete|abandon|reset|allow|track> <quest> [reason...]}</li>
 *   <li>{@code objective <quest> <objective> <value> [reason...]}</li>
 *   <li>{@code tag <add|remove> <tag> [reason...]} and {@code var <set|remove> <key> [value] [reason...]}
 *       for v1 player state.</li>
 *   <li>{@code story <var|tag|restart|rewind> ...} for 2.0 story state.</li>
 * </ul>
 *
 * <p>Reading needs {@code mysticquests.command.admin.debug}; changing needs
 * {@code mysticquests.command.admin.player}. Every change is audited with its reason; a clear must
 * give one, other changes default to naming this command.
 */
final class PlayerCommand {
    static final String READ_PERMISSION = "mysticquests.command.admin.debug";
    static final String WRITE_PERMISSION = PlayerStateAdmin.PERMISSION;
    static final List<String> ACTIONS = List.of("info", "clear", "quest", "objective", "tag", "var", "story");
    private static final String DEFAULT_REASON = "/mquest player";

    private static final String TEXT = "#EEF3FC";
    private static final String MUTED = "#9AA7BD";
    private static final String GOLD = "#CCAA58";
    private static final String GREEN = "#7BE495";
    private static final String RED = "#F2545B";

    private final MysticQuestsRuntime runtime;

    PlayerCommand(MysticQuestsRuntime runtime) {
        this.runtime = runtime;
    }

    void execute(CommandContext context, String[] args) {
        if (!permitted(context, READ_PERMISSION)) {
            return;
        }
        if (args.length < 2) {
            usage(context);
            return;
        }
        UUID player = player(context, args[1]);
        if (player == null) {
            return;
        }
        String action = args.length > 2 ? args[2].toLowerCase(Locale.ROOT) : "info";
        if (!action.equals("info") && !permitted(context, WRITE_PERMISSION)) {
            return;
        }
        PlayerStateAdmin admin = runtime.playerAdmin();
        String actor = context.isPlayer() ? context.sender().getUuid().toString() : "console";
        switch (action) {
            case "info" -> info(context, admin.snapshot(player));
            case "clear" -> clear(context, admin, actor, player, args);
            case "quest" -> quest(context, admin, actor, player, args);
            case "objective" -> {
                if (args.length < 6) {
                    send(context, "/mquest player <player> objective <quest> <objective> <value> [reason...]", MUTED);
                    return;
                }
                int value;
                try {
                    value = Integer.parseInt(args[5]);
                } catch (NumberFormatException notNumber) {
                    send(context, "The value must be a whole number.", RED);
                    return;
                }
                report(context, admin.setObjective(actor, player, args[3], args[4], value, reason(args, 6)));
            }
            case "tag" -> {
                if (args.length < 5) {
                    send(context, "/mquest player <player> tag <add|remove> <tag> [reason...]", MUTED);
                    return;
                }
                report(context, args[3].equalsIgnoreCase("remove")
                        ? admin.removeTag(actor, player, args[4], reason(args, 5))
                        : admin.addTag(actor, player, args[4], reason(args, 5)));
            }
            case "var", "variable" -> variable(context, admin, actor, player, args);
            case "story" -> story(context, admin, actor, player, args);
            default -> usage(context);
        }
    }

    private void clear(CommandContext context, PlayerStateAdmin admin, String actor, UUID player, String[] args) {
        if (args.length < 5) {
            send(context, "/mquest player <player> clear <all|progress|quests,tags,variables,story,sessions,preferences> <reason...>", MUTED);
            send(context, "A reason is required; it is written to the audit trail.", MUTED);
            return;
        }
        Set<Part> parts = parts(args[3]);
        if (parts == null) {
            send(context, "Unknown part in " + args[3] + ". Parts: quests, tags, variables, story, sessions, preferences.", RED);
            return;
        }
        report(context, admin.clear(actor, player, parts, String.join(" ", Arrays.copyOfRange(args, 4, args.length))));
    }

    @Nullable
    static Set<Part> parts(String raw) {
        String key = raw.toLowerCase(Locale.ROOT);
        if (key.equals("all")) {
            return EnumSet.allOf(Part.class);
        }
        if (key.equals("progress")) {
            return Part.progress();
        }
        Set<Part> parts = EnumSet.noneOf(Part.class);
        for (String token : key.split(",")) {
            Part part = Part.parse(token);
            if (part == null) {
                return null;
            }
            parts.add(part);
        }
        return parts;
    }

    private void quest(CommandContext context, PlayerStateAdmin admin, String actor, UUID player, String[] args) {
        if (args.length < 5) {
            send(context, "/mquest player <player> quest <start|complete|abandon|reset|allow|track> <quest> [reason...]", MUTED);
            return;
        }
        String quest = args[4];
        String reason = reason(args, 5);
        Outcome outcome = switch (args[3].toLowerCase(Locale.ROOT)) {
            case "start", "grant" -> admin.startQuest(actor, player, quest, reason);
            case "complete" -> admin.completeQuest(actor, player, quest, reason);
            case "abandon" -> admin.abandonQuest(actor, player, quest, reason);
            case "reset" -> admin.resetQuest(actor, player, quest, reason);
            case "allow", "reaccept" -> admin.allowAgain(actor, player, quest, reason);
            case "track" -> admin.trackQuest(actor, player, quest, reason);
            default -> null;
        };
        if (outcome == null) {
            send(context, "Quest actions: start, complete, abandon, reset, allow, track.", RED);
            return;
        }
        report(context, outcome);
    }

    private void variable(CommandContext context, PlayerStateAdmin admin, String actor, UUID player, String[] args) {
        if (args.length < 5) {
            send(context, "/mquest player <player> var <set|remove> <key> [value] [reason...]", MUTED);
            return;
        }
        if (args[3].equalsIgnoreCase("remove")) {
            report(context, admin.removeVariable(actor, player, args[4], reason(args, 5)));
            return;
        }
        String value = args.length > 5 ? args[5] : "";
        report(context, admin.setVariable(actor, player, args[4], value, reason(args, 6)));
    }

    private void story(CommandContext context, PlayerStateAdmin admin, String actor, UUID player, String[] args) {
        String usage = "/mquest player <player> story <var set <id> <value>|var remove <owner> <id>|tag add <id>|tag remove <owner> <id>|restart <story>|rewind <label>> [reason...]";
        if (args.length < 5) {
            send(context, usage, MUTED);
            return;
        }
        String kind = args[3].toLowerCase(Locale.ROOT);
        String verb = args[4].toLowerCase(Locale.ROOT);
        Outcome outcome = switch (kind) {
            case "restart" -> admin.restartStory(actor, player, args[4], reason(args, 5));
            case "rewind" -> admin.rewind(actor, player, args[4], reason(args, 5));
            case "var", "variable" -> {
                if (verb.equals("set") && args.length >= 7) {
                    yield admin.setStoryVariable(actor, player, args[5], args[6], reason(args, 7));
                }
                if (verb.equals("remove") && args.length >= 7) {
                    yield admin.removeStoryVariable(actor, player, args[5], args[6], reason(args, 7));
                }
                yield null;
            }
            case "tag" -> {
                if (verb.equals("add") && args.length >= 6) {
                    yield admin.addStoryTag(actor, player, args[5], reason(args, 6));
                }
                if (verb.equals("remove") && args.length >= 7) {
                    yield admin.removeStoryTag(actor, player, args[5], args[6], reason(args, 7));
                }
                yield null;
            }
            default -> null;
        };
        if (outcome == null) {
            send(context, usage, MUTED);
            send(context, "<owner> is the owner shown by /mquest player <player>, such as player/<uuid>.", MUTED);
            return;
        }
        report(context, outcome);
    }

    private void info(CommandContext context, PlayerStateAdmin.Snapshot snapshot) {
        send(context, snapshot.name() + (snapshot.online() ? "  (online)" : "  (offline)") + "  " + snapshot.player(), GOLD);
        send(context, "Quests: " + (snapshot.quests().isEmpty() ? "none" : ""), TEXT);
        for (PlayerStateAdmin.QuestLine quest : snapshot.quests()) {
            send(context, "  " + quest.status().name().toLowerCase(Locale.ROOT) + "  " + quest.questId() + "  " + quest.name()
                    + (quest.detail().isBlank() ? "" : "  -  " + quest.detail()), MUTED);
        }
        send(context, "Tags: " + (snapshot.tags().isEmpty() ? "none" : String.join(", ", snapshot.tags())), TEXT);
        send(context, "Variables: " + (snapshot.variables().isEmpty() ? "none" : snapshot.variables().toString()), TEXT);
        if (!snapshot.narrative()) {
            send(context, "Story runtime: not running.", MUTED);
            return;
        }
        send(context, "Story variables: " + (snapshot.storyVariables().isEmpty() ? "none" : ""), TEXT);
        snapshot.storyVariables().forEach(line -> send(context, "  " + line.owner() + "  " + line.id() + " = " + line.value()
                + (line.preference() ? "  (setting)" : ""), MUTED));
        send(context, "Story tags: " + (snapshot.storyTags().isEmpty() ? "none" : ""), TEXT);
        snapshot.storyTags().forEach(line -> send(context, "  " + line.owner() + "  " + line.id()
                + (line.value().isBlank() ? "" : "  " + line.value()), MUTED));
        send(context, "Story sessions: " + (snapshot.sessions().isEmpty() ? "none" : ""), TEXT);
        snapshot.sessions().forEach(session -> send(context, "  " + session.story() + "  " + session.status()
                + (session.party() ? "  (party)" : "") + (session.node().isBlank() ? "" : "  at " + session.node()), MUTED));
        snapshot.problems().forEach(problem -> send(context, problem, RED));
    }

    private static void report(CommandContext context, Outcome outcome) {
        send(context, outcome.message(), outcome.ok() ? GREEN : RED);
    }

    private static String reason(String[] args, int from) {
        return args.length > from ? String.join(" ", Arrays.copyOfRange(args, from, args.length)) : DEFAULT_REASON;
    }

    @Nullable
    private UUID player(CommandContext context, String token) {
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
            send(context, "Unknown player: " + token + ". Use an online name, a UUID, or self.", RED);
            return null;
        }
    }

    private static void usage(CommandContext context) {
        send(context, "/mquest player <player> [info|clear|quest|objective|tag|var|story] ...", MUTED);
        send(context, "Example: /mquest player Alphine clear progress testing the intro again", MUTED);
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
