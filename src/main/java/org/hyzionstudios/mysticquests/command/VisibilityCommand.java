package org.hyzionstudios.mysticquests.command;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.VisibilityService;

import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.CommandContext;

import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * {@code /mq visibility ...}: staff presentation bypass (§6.1 of the 2.0 specification).
 *
 * <ul>
 *   <li>{@code bypass} toggles, {@code bypass on|off} sets. Needs
 *       {@link VisibilityService#BYPASS_PERMISSION}; {@link VisibilityService#BYPASS_ALWAYS_PERMISSION}
 *       holds it on.</li>
 *   <li>{@code status [player]} shows bypass state and every quest hide affecting that viewer, with
 *       its reasons, including MysticVanish's. Needs the bypass permission or the debug permission.</li>
 * </ul>
 *
 * <p>Bypass changes only what the staff member sees. It never advances, resets or rewrites quest
 * state, and never changes what anyone else sees. Every toggle is logged with the actor anyway, so
 * staff observation stays accountable.
 */
final class VisibilityCommand {
    static final List<String> SUBCOMMANDS = List.of("bypass", "status");

    private static final String DEBUG_PERMISSION = "mysticquests.command.admin.debug";
    private static final String TEXT = "#EEF3FC";
    private static final String MUTED = "#9AA7BD";
    private static final String GREEN = "#7BE495";
    private static final String ORANGE = "#F5A742";
    private static final String RED = "#F2545B";
    private static final int STATUS_LIMIT = 10;

    private final MysticQuestsRuntime runtime;

    VisibilityCommand(MysticQuestsRuntime runtime) {
        this.runtime = runtime;
    }

    void execute(CommandContext context, String[] args) {
        VisibilityService visibility = runtime.visibility();
        if (visibility == null) {
            send(context, "Visibility is not running.", RED);
            return;
        }
        String sub = args.length >= 2 ? args[1].toLowerCase(Locale.ROOT) : "status";
        switch (sub) {
            case "bypass" -> bypass(context, visibility, args);
            case "status" -> status(context, visibility, args);
            default -> send(context, "/mq visibility <bypass [on|off]|status [player]>", MUTED);
        }
    }

    private void bypass(CommandContext context, VisibilityService visibility, String[] args) {
        if (!context.isPlayer()) {
            send(context, "Only players can bypass quest visibility.", ORANGE);
            return;
        }
        UUID self = context.sender().getUuid();
        boolean always = context.sender().hasPermission(VisibilityService.BYPASS_ALWAYS_PERMISSION);
        if (!always && !context.sender().hasPermission(VisibilityService.BYPASS_PERMISSION)
                && !context.sender().hasPermission("mysticquests.admin")) {
            send(context, "Missing permission: " + VisibilityService.BYPASS_PERMISSION, RED);
            return;
        }
        if (args.length >= 3 && !args[2].equalsIgnoreCase("on") && !args[2].equalsIgnoreCase("off")) {
            send(context, "Use on or off.", ORANGE);
            return;
        }
        // Re-read the permission here, so a role change takes effect without a relog.
        visibility.setBypassForced(self, always);
        boolean target = args.length >= 3
                ? args[2].equalsIgnoreCase("on")
                : !visibility.isBypassing(self);
        if (!target && visibility.isBypassForced(self)) {
            send(context, "Bypass is held on by " + VisibilityService.BYPASS_ALWAYS_PERMISSION + ".", ORANGE);
            return;
        }
        visibility.setBypass(self, target);
        runtime.plugin().getLogger().at(Level.INFO).log(
                "[MysticQuests visibility] " + self + " turned quest visibility bypass " + (target ? "on" : "off") + ".");
        send(context, target
                ? "Visibility bypass on: you see everyone quests hide from you. Quest state and what others see are unchanged."
                : "Visibility bypass off: quest visibility applies to you again.", GREEN);
    }

    private void status(CommandContext context, VisibilityService visibility, String[] args) {
        if (!context.sender().hasPermission(VisibilityService.BYPASS_PERMISSION)
                && !context.sender().hasPermission(VisibilityService.BYPASS_ALWAYS_PERMISSION)
                && !context.sender().hasPermission(DEBUG_PERMISSION)
                && !context.sender().hasPermission("mysticquests.admin")) {
            send(context, "Missing permission: " + VisibilityService.BYPASS_PERMISSION, RED);
            return;
        }
        UUID viewer = args.length >= 3 ? player(context, args[2]) : context.sender().getUuid();
        if (viewer == null) {
            return;
        }
        String bypass = visibility.isBypassForced(viewer) ? "on (held by permission)"
                : visibility.isBypassing(viewer) ? "on" : "off";
        Set<UUID> hidden = visibility.hiddenFrom(viewer);
        send(context, "Bypass: " + bypass + ". Quest hides as viewer: " + hidden.size()
                + ", presented: " + visibility.presentedHiddenFrom(viewer).size() + ".", TEXT);
        hidden.stream().limit(STATUS_LIMIT).forEach(target ->
                send(context, "  " + target + " " + visibility.reasons(viewer, target), MUTED));
        if (hidden.size() > STATUS_LIMIT) {
            send(context, "  ... " + (hidden.size() - STATUS_LIMIT) + " more", MUTED);
        }
    }

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
            send(context, "Unknown player: " + token, RED);
            return null;
        }
    }

    private static void send(CommandContext context, String text, String color) {
        context.sendMessage(Message.raw(text).color(color));
    }
}
