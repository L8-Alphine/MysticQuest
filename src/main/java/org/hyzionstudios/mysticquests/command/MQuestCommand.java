package org.hyzionstudios.mysticquests.command;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.integration.HyCitizensBridge.CitizenView;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.QuestResult;

import com.fasterxml.jackson.databind.node.TextNode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandSender;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.command.system.ParseResult;
import com.hypixel.hytale.server.core.command.system.arguments.types.ArgTypes;
import com.hypixel.hytale.server.core.command.system.arguments.types.SingleArgumentType;
import com.hypixel.hytale.server.core.command.system.suggestion.SuggestionResult;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.Arrays;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.function.BiConsumer;
import java.util.function.Supplier;

public final class MQuestCommand extends AbstractCommand {
    private static final String GOLD = "#F5C842";
    private static final String GREEN = "#3DD68C";
    private static final String ORANGE = "#F59E3A";
    private static final String RED = "#FF6B6B";
    private static final String BLUE = "#7DB7FF";
    private static final String MUTED = "#9AAFC7";
    private static final String TEXT = "#EEF3FC";

    private final MysticQuestsRuntime runtime;
    private final String defaultSubcommand;

    public MQuestCommand(MysticQuestsRuntime runtime) {
        this(runtime, "mquest", "");
    }

    public MQuestCommand(MysticQuestsRuntime runtime, String name, String defaultSubcommand) {
        super(name, "MysticQuests administration and journal commands");
        this.runtime = runtime;
        this.defaultSubcommand = defaultSubcommand == null ? "" : defaultSubcommand;
        setAllowsExtraArguments(true);
        if (defaultSubcommand.isBlank()) {
            registerCompletions();
        }
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        String[] args = parseArgs(context.getInputString());
        if (!defaultSubcommand.isBlank() && (args.length == 0 || !isSubcommand(args[0]))) {
            String[] journalArgs = new String[args.length + 1];
            journalArgs[0] = defaultSubcommand;
            System.arraycopy(args, 0, journalArgs, 1, args.length);
            args = journalArgs;
        }
        if (args.length == 0) {
            sendHelp(context);
            return CompletableFuture.completedFuture(null);
        }
        CompletableFuture<Void> future = switch (args[0].toLowerCase()) {
            case "reload" -> completed(reload(context));
            case "admin", "editor", "studio" -> questStudio(context);
            case "start" -> completed(start(context, args));
            case "complete" -> completed(complete(context, args));
            case "progress" -> journal(context, args, false);
            case "journal" -> journal(context, args, true);
            case "menu", "quest", "quests" -> questMenu(context);
            case "track" -> completed(track(context, args));
            case "untrack" -> completed(untrack(context));
            case "abandon" -> completed(abandon(context, args));
            case "reaccept" -> completed(reaccept(context, args));
            case "cancel" -> completed(cancel(context, args));
            case "entity" -> entity(context, args);
            case "state" -> state(context, args);
            case "block" -> block(context, args);
            case "volume" -> completed(volume(context, args));
            case "hycitizens" -> hyCitizens(context, args);
            case "debug" -> completed(debug(context, args));
            default -> completed(sendHelp(context));
        };
        runtime.plugin().getTaskRegistry().registerTask(future);
        return future;
    }

    private CompletableFuture<Void> completed(Void ignored) {
        return CompletableFuture.completedFuture(null);
    }

    private String[] parseArgs(String input) {
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] raw = input.trim().split("\\s+");
        if (raw.length > 0 && (raw[0].equalsIgnoreCase("mquest")
                || raw[0].equalsIgnoreCase("journal")
                || raw[0].equalsIgnoreCase("quest")
                || raw[0].equalsIgnoreCase("quests"))) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private boolean isSubcommand(String value) {
        return switch (value.toLowerCase()) {
            case "reload", "admin", "editor", "studio", "start", "complete", "progress", "journal", "menu", "quest", "quests", "track", "untrack", "abandon", "reaccept", "cancel", "entity", "state", "block", "volume", "hycitizens", "debug" -> true;
            default -> false;
        };
    }

    private void registerCompletions() {
        addSubCommand(route("reload"));
        addSubCommand(route("admin"));
        addSubCommand(route("editor"));
        RouteCommand start = route("start");
        start.withRequiredArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        start.withRequiredArg("quest", "Quest ID", suggested("quest", this::questIds));
        addSubCommand(start);
        RouteCommand complete = route("complete");
        complete.withRequiredArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        complete.withRequiredArg("quest", "Quest ID", suggested("quest", this::questIds));
        addSubCommand(complete);
        RouteCommand progress = route("progress");
        progress.withOptionalArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        addSubCommand(progress);
        RouteCommand journal = route("journal");
        journal.withOptionalArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        addSubCommand(journal);
        addSubCommand(route("menu"));
        addSubCommand(route("quest"));
        addSubCommand(route("quests"));
        RouteCommand track = route("track");
        track.withRequiredArg("quest", "Quest ID", suggested("quest", this::questIds));
        addSubCommand(track);
        addSubCommand(route("untrack"));
        RouteCommand abandon = route("abandon");
        abandon.withRequiredArg("quest", "Quest ID", suggested("quest", this::questIds));
        addSubCommand(abandon);
        RouteCommand reaccept = route("reaccept");
        reaccept.withRequiredArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        reaccept.withOptionalArg("quest", "Quest ID", suggested("quest", this::questIds));
        addSubCommand(reaccept);
        RouteCommand cancel = route("cancel");
        cancel.withRequiredArg("player", "Player UUID, name, or self", suggested("player", this::playerTargets));
        cancel.withRequiredArg("canceler", "Canceler ID", suggested("canceler", () -> runtime.content().cancelers().keySet()));
        addSubCommand(cancel);

        RouteCommand entity = route("entity");
        entity.addSubCommand(route("uuid"));
        RouteCommand scan = route("scan");
        scan.withOptionalArg("radius", "Radius", ArgTypes.DOUBLE);
        entity.addSubCommand(scan);
        RouteCommand bind = route("bind");
        bind.withRequiredArg("conversation", "Conversation ID", suggested("conversation", this::conversationIds));
        entity.addSubCommand(bind);
        addSubCommand(entity);

        RouteCommand state = route("state");
        RouteCommand stateGet = route("get");
        stateGet.withRequiredArg("scope", "State scope", suggested("scope", this::scopes));
        stateGet.withRequiredArg("target", "State target", suggested("target", this::stateTargets));
        stateGet.withOptionalArg("key", "Variable key", ArgTypes.STRING);
        RouteCommand stateTag = route("tag");
        stateTag.withRequiredArg("scope", "State scope", suggested("scope", this::scopes));
        stateTag.withRequiredArg("target", "State target", suggested("target", this::stateTargets));
        stateTag.withRequiredArg("mutation", "Tag action", suggested("mutation", () -> List.of("add", "remove", "has", "list")));
        stateTag.withOptionalArg("tag", "Tag", ArgTypes.STRING);
        state.addSubCommand(stateGet);
        state.addSubCommand(stateTag);
        addSubCommand(state);

        RouteCommand block = route("block");
        block.addSubCommand(route("uuid"));
        addSubCommand(block);

        RouteCommand volume = route("volume");
        volume.addSubCommand(route("uuid"));
        RouteCommand volumeState = route("state");
        volumeState.withRequiredArg("volume", "Volume key", ArgTypes.STRING);
        volumeState.withRequiredArg("action", "Action", suggested("action", () -> List.of("get")));
        volumeState.withOptionalArg("key", "Variable key", ArgTypes.STRING);
        RouteCommand volumeTag = route("tag");
        volumeTag.withRequiredArg("volume", "Volume key", ArgTypes.STRING);
        volumeTag.withRequiredArg("mutation", "Tag action", suggested("mutation", () -> List.of("add", "remove", "has", "list")));
        volumeTag.withOptionalArg("tag", "Tag", ArgTypes.STRING);
        volume.addSubCommand(volumeState);
        volume.addSubCommand(volumeTag);
        addSubCommand(volume);

        RouteCommand hyCitizens = route("hycitizens");
        RouteCommand hyCitizensList = route("list");
        hyCitizensList.withOptionalArg("mode", "List mode", suggested("mode", () -> List.of("near", "all")));
        hyCitizens.addSubCommand(hyCitizensList);
        RouteCommand hyCitizensInfo = route("info");
        hyCitizensInfo.withRequiredArg("id", "Citizen ID", suggested("id", this::hyCitizenIds));
        hyCitizens.addSubCommand(hyCitizensInfo);
        RouteCommand hyCitizensBind = route("bind");
        hyCitizensBind.withRequiredArg("conversation", "Conversation ID", suggested("conversation", this::conversationIds));
        hyCitizensBind.withRequiredArg("id", "Citizen ID", suggested("id", this::hyCitizenIds));
        hyCitizens.addSubCommand(hyCitizensBind);
        addSubCommand(hyCitizens);

        RouteCommand debug = route("debug");
        debug.withOptionalArg("target", "Debug target", suggested("target", () -> List.of("package", "quest", "player")));
        debug.withOptionalArg("id", "ID", ArgTypes.STRING);
        addSubCommand(debug);
    }

    private RouteCommand route(String name) {
        return new RouteCommand(name);
    }

    private SuggestedStringArgument suggested(String name, Supplier<Collection<String>> values) {
        return new SuggestedStringArgument(name, values);
    }

    private Collection<String> questIds() {
        return runtime.content().quests().keySet();
    }

    private Collection<String> conversationIds() {
        return runtime.content().conversations().keySet();
    }

    private Collection<String> scopes() {
        return List.of("player", "global", "entity", "block", "volume");
    }

    private Collection<String> playerTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("self");
        targets.addAll(runtime.onlinePlayerNames());
        return targets;
    }

    private Collection<String> stateTargets() {
        LinkedHashSet<String> targets = new LinkedHashSet<>();
        targets.add("self");
        targets.add("<target>");
        targets.addAll(runtime.onlinePlayerNames());
        runtime.content().conversations().values().stream()
                .filter(conversation -> conversation.entity() != null)
                .map(conversation -> conversation.entity().uuid())
                .filter(uuid -> uuid != null && !uuid.isBlank())
                .forEach(targets::add);
        return targets;
    }

    private Collection<String> hyCitizenIds() {
        if (runtime.hyCitizensBridge() == null || !runtime.hyCitizensBridge().available()) {
            return List.of();
        }
        return runtime.hyCitizensBridge().citizens().stream()
                .map(CitizenView::id)
                .filter(id -> id != null && !id.isBlank())
                .toList();
    }

    private Void reload(CommandContext context) {
        if (!requireAdmin(context, "mysticquests.command.admin.reload")) {
            return null;
        }
        try {
            int count = runtime.reloadContent().quests().size();
            success(context, "MysticQuests reloaded " + count + " quests.");
        } catch (IOException exception) {
            error(context, "MysticQuests reload failed: " + exception.getMessage());
        }
        return null;
    }

    private CompletableFuture<Void> questStudio(CommandContext context) {
        if (!requireAdmin(context, "mysticquests.command.admin.editor")) {
            return CompletableFuture.completedFuture(null);
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can open the in-game quest studio.");
            return CompletableFuture.completedFuture(null);
        }
        return onPlayerWorld(context, () -> {
            if (!runtime.uiService().openAdminStudio(context)) {
                warn(context, "Could not open the MysticQuests quest studio.");
            }
        });
    }

    private Void start(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return null;
        }
        if (args.length < 3) {
            usage(context, "Usage: /mquest start <player-uuid|self> <quest>");
            return null;
        }
        UUID playerId = playerId(context, args[1]);
        if (playerId == null) {
            return null;
        }
        QuestResult result = runtime.questService().startQuest(playerId, args[2]);
        result(context, result);
        return null;
    }

    private Void complete(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return null;
        }
        if (args.length < 3) {
            usage(context, "Usage: /mquest complete <player-uuid|self> <quest>");
            return null;
        }
        UUID playerId = playerId(context, args[1]);
        if (playerId == null) {
            return null;
        }
        QuestResult result = runtime.questService().completeQuest(playerId, args[2]);
        result(context, result);
        return null;
    }

    /**
     * Staff override: clears an abandonment so the player may take the quest again immediately,
     * bypassing any cooldown or re-accept conditions. With no quest argument, lists what is
     * abandoned so staff can see the IDs to clear.
     */
    private Void reaccept(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return null;
        }
        if (args.length < 2) {
            usage(context, "Usage: /mquest reaccept <player-uuid|self> [quest]");
            return null;
        }
        UUID playerId = playerId(context, args[1]);
        if (playerId == null) {
            return null;
        }
        if (args.length < 3) {
            listAbandoned(context, playerId);
            return null;
        }
        QuestResult result = runtime.questService().clearAbandoned(playerId, args[2]);
        if (result.success()) {
            // Bible 17.2: staff overrides are attributable.
            runtime.plugin().getLogger().at(java.util.logging.Level.INFO).log(
                    "MysticQuests audit: " + context.sender().getUsername() + " (" + context.sender().getUuid() + ")"
                            + " cleared abandonment of " + args[2] + " for " + playerId + ".");
        }
        result(context, result);
        return null;
    }

    private void listAbandoned(CommandContext context, UUID playerId) {
        Map<String, Instant> abandoned = runtime.questService().abandonedRecords(playerId);
        if (abandoned.isEmpty()) {
            info(context, "No abandoned quests recorded for " + playerId + ".");
            return;
        }
        context.sendMessage(Message.raw("Abandoned quests for " + playerId + ":").color(GOLD));
        abandoned.entrySet().stream()
                .sorted(Map.Entry.comparingByValue(Comparator.reverseOrder()))
                .forEach(entry -> {
                    boolean allowed = runtime.questService().reacceptState(playerId, entry.getKey()).allowed();
                    context.sendMessage(Message.join(
                            Message.raw(" - " + entry.getKey()).color(TEXT),
                            Message.raw("  " + entry.getValue()).color(MUTED),
                            Message.raw(allowed ? "  [can retake]" : "  [locked]").color(allowed ? GREEN : ORANGE)));
                });
        info(context, "Clear one with /mquest reaccept <player> <quest>.");
    }

    private Void cancel(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return null;
        }
        if (args.length < 3) {
            usage(context, "Usage: /mquest cancel <player-uuid|self> <canceler>");
            return null;
        }
        UUID playerId = playerId(context, args[1]);
        if (playerId != null) {
            result(context, runtime.questService().cancelQuest(playerId, "", args[2]));
        }
        return null;
    }

    private CompletableFuture<Void> journal(CommandContext context, String[] args, boolean openUi) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return CompletableFuture.completedFuture(null);
        }
        if (openUi && context.isPlayer() && (args.length < 2 || args[1].equalsIgnoreCase("self"))) {
            return onPlayerWorld(context, () -> {
                if (!runtime.uiService().openJournal(context)) {
                    sendJournalText(context, args);
                }
            });
        }
        sendJournalText(context, args);
        return CompletableFuture.completedFuture(null);
    }

    private CompletableFuture<Void> questMenu(CommandContext context) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return CompletableFuture.completedFuture(null);
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can open the quest menu.");
            return CompletableFuture.completedFuture(null);
        }
        return onPlayerWorld(context, () -> {
            if (!runtime.uiService().openQuestMenu(context)) {
                warn(context, "Could not open the quest menu.");
            }
        });
    }

    private void sendJournalText(CommandContext context, String[] args) {
        UUID playerId = args.length >= 2 ? playerId(context, args[1]) : context.sender().getUuid();
        if (playerId == null) {
            return;
        }
        List<JournalEntry> entries = runtime.questService().journal(playerId);
        if (entries.isEmpty()) {
            warn(context, "No active quests.");
            return;
        }
        for (JournalEntry entry : entries) {
            context.sendMessage(Message.join(
                    Message.raw(entry.displayName()).color(GOLD),
                    Message.raw(" (" + entry.questId() + ")").color(MUTED)));
            for (ObjectiveView objective : entry.objectives()) {
                context.sendMessage(Message.join(
                        Message.raw(" - " + objective.line()).color(objective.complete() ? GREEN : MUTED)));
            }
        }
    }

    private Void track(CommandContext context, String[] args) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return null;
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can track quests.");
            return null;
        }
        if (args.length < 2) {
            usage(context, "Usage: /mquest track <quest>");
            return null;
        }
        QuestResult result = runtime.questService().trackQuest(context.sender().getUuid(), args[1]);
        result(context, result);
        return null;
    }

    private Void abandon(CommandContext context, String[] args) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return null;
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can abandon quests.");
            return null;
        }
        if (args.length < 2) {
            usage(context, "Usage: /mquest abandon <quest>");
            return null;
        }
        result(context, runtime.questService().abandonQuest(context.sender().getUuid(), args[1]));
        return null;
    }

    private Void untrack(CommandContext context) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return null;
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can untrack quests.");
            return null;
        }
        QuestResult result = runtime.questService().untrackQuest(context.sender().getUuid());
        result(context, result);
        return null;
    }

    private CompletableFuture<Void> entity(CommandContext context, String[] args) {
        if (args.length < 2) {
            usage(context, "Usage: /mquest entity <uuid|bind|scan> [conversation|radius]");
            return CompletableFuture.completedFuture(null);
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can target entities.");
            return CompletableFuture.completedFuture(null);
        }
        if (!requireAdmin(context, "mysticquests.command.admin.entity")) {
            return CompletableFuture.completedFuture(null);
        }
        if (args[1].equalsIgnoreCase("scan")) {
            double radius = args.length >= 3 ? parseRadius(args[2], 5.0D) : 5.0D;
            return onPlayerWorld(context, () -> scanEntities(context, radius));
        }
        return onPlayerWorld(context, () -> {
            TargetEntity target = targetEntity(context);
            if (target == null) {
                return;
            }
            switch (args[1].toLowerCase()) {
                case "uuid" -> {
                    info(context, "Entity details:");
                    valueLine(context, "UUID: ", target.uuid(), GOLD);
                    info(context, "Entity name: " + target.name());
                    info(context, "Entity type: " + target.type());
                    info(context, "Interactable: " + target.interactable());
                    if (target.distance() >= 0.0D) {
                        info(context, "Distance: " + format(target.distance()) + " blocks");
                    }
                    info(context, "Location: " + target.worldId() + " @ "
                            + format(target.x()) + ", " + format(target.y()) + ", " + format(target.z()));
                    Set<String> tags = runtime.scopedStateService().tags("entity", target.uuid());
                    Map<String, String> variables = runtime.scopedStateService().variables("entity", target.uuid());
                    info(context, "Tags: " + (tags.isEmpty() ? "[]" : tags));
                    info(context, "Variables: " + (variables.isEmpty() ? "{}" : variables));
                    info(context, "Conversations: " + conversationsFor(target));
                    valueLine(context, "JSON: ", entityJson(target.uuid(), target.type(), target.name()), TEXT);
                }
                case "bind" -> {
                    if (args.length < 3) {
                        usage(context, "Usage: /mquest entity bind <conversation>");
                        return;
                    }
                    info(context, "Conversation " + args[2] + " entity binding:");
                    String json = entityJson(target.uuid(), target.type(), target.name());
                    valueLine(context, "", json, TEXT);
                }
                default -> usage(context, "Usage: /mquest entity <uuid|bind|scan> [conversation|radius]");
            }
        });
    }

    private Void debug(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.debug")) {
            return null;
        }
        if (args.length < 2) {
            info(context, "Loaded quests: " + runtime.content().quests().keySet());
            return null;
        }
        switch (args[1].toLowerCase()) {
            case "package" -> info(context, "Loaded packages: " + runtime.content().packages());
            case "quest" -> info(context, "Loaded quests: " + runtime.content().quests().keySet());
            case "player" -> {
                UUID playerId = args.length >= 3 ? playerId(context, args[2]) : context.sender().getUuid();
                if (playerId == null) {
                    return null;
                }
                info(context, "Active journal entries: " + runtime.questService().journal(playerId).size());
            }
            default -> warn(context, "Unknown debug target.");
        }
        return null;
    }

    private UUID playerId(CommandContext context, String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase("self")) {
            return context.sender().getUuid();
        }
        return runtime.resolveOnlinePlayer(token).orElseGet(() -> {
            try {
                return UUID.fromString(token);
            } catch (IllegalArgumentException exception) {
                error(context, "Unknown player: " + token);
                return null;
            }
        });
    }

    private Void sendHelp(CommandContext context) {
        context.sendMessage(Message.join(
                Message.raw("/quest").color(GOLD),
                Message.raw(" — quest board: browse and accept new quests").color(TEXT)));
        context.sendMessage(Message.join(
                Message.raw("/journal").color(GOLD),
                Message.raw(" — quest log: current, completed, and abandoned quests").color(TEXT)));
        context.sendMessage(Message.join(
                Message.raw("/mquest ").color(GOLD),
                Message.raw("track, untrack, abandon, progress").color(TEXT)));
        if (context.sender().hasPermission("mysticquests.admin")) {
            context.sendMessage(Message.join(
                    Message.raw("Admin: ").color(ORANGE),
                    Message.raw("admin/editor, reload, start, complete, reaccept, entity, state, block, volume, hycitizens, debug").color(MUTED)));
        }
        return null;
    }

    private CompletableFuture<Void> state(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.debug")) {
            return CompletableFuture.completedFuture(null);
        }
        if (args.length < 4) {
            usage(context, "Usage: /mquest state <get|tag> <scope> <target> ...");
            return CompletableFuture.completedFuture(null);
        }
        String action = args[1].toLowerCase();
        String scope = ScopedStateService.normalizeScope(args[2]);
        if (args[3].equalsIgnoreCase("<target>") && (scope.equals("entity") || scope.equals("block"))) {
            if (!context.isPlayer()) {
                warn(context, "Only players can resolve <target>.");
                return CompletableFuture.completedFuture(null);
            }
            return onPlayerWorld(context, () -> {
                String target = resolveLookedTarget(context, scope);
                if (target != null && !target.isBlank()) {
                    stateResolved(context, args, action, scope, target);
                }
            });
        }
        String target = targetToken(context, scope, args[3]);
        return completed(stateResolved(context, args, action, scope, target));
    }

    private Void stateResolved(CommandContext context, String[] args, String action, String scope, String target) {
        if (action.equals("get")) {
            if (args.length >= 5) {
                info(context, args[4] + " = " + runtime.scopedStateService().variables(scope, target).getOrDefault(args[4], ""));
            } else {
                info(context, "Tags: " + runtime.scopedStateService().tags(scope, target));
                info(context, "Variables: " + runtime.scopedStateService().variables(scope, target));
            }
            return null;
        }
        if (!action.equals("tag") || args.length < 5) {
            usage(context, "Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]");
            return null;
        }
        String mutation = args[4].toLowerCase();
        if (mutation.equals("list")) {
            info(context, "Tags: " + runtime.scopedStateService().tags(scope, target));
            return null;
        }
        if (args.length < 6) {
            usage(context, "Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]");
            return null;
        }
        EventDefinition event = scopedEvent(scope, target, args[5]);
        UUID actor = context.sender().getUuid();
        switch (mutation) {
            case "add" -> {
                runtime.scopedStateService().addTag(actor, event, QuestTargetContext.none());
                success(context, "Added tag " + args[5] + " to " + scope + ":" + target);
            }
            case "remove" -> {
                runtime.scopedStateService().removeTag(actor, event, QuestTargetContext.none());
                success(context, "Removed tag " + args[5] + " from " + scope + ":" + target);
            }
            case "has" -> info(context, Boolean.toString(runtime.scopedStateService().hasTag(actor, event, QuestTargetContext.none())));
            default -> usage(context, "Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]");
        }
        return null;
    }

    private CompletableFuture<Void> block(CommandContext context, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("uuid")) {
            usage(context, "Usage: /mquest block uuid");
            return CompletableFuture.completedFuture(null);
        }
        if (!requireAdmin(context, "mysticquests.command.admin.entity")) {
            return CompletableFuture.completedFuture(null);
        }
        if (!context.isPlayer()) {
            warn(context, "Only players can target blocks.");
            return CompletableFuture.completedFuture(null);
        }
        return onPlayerWorld(context, () -> targetBlock(context));
    }

    private void targetBlock(CommandContext context) {
        String key = targetBlockKey(context);
        if (key == null) {
            return;
        }
        info(context, "Block key: " + key);
    }

    private String targetBlockKey(CommandContext context) {
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        org.joml.Vector3i block = TargetUtil.getTargetBlock(playerRef, 8.0D, store);
        if (block == null) {
            warn(context, "No block found in your line of sight.");
            return null;
        }
        com.hypixel.hytale.server.core.universe.PlayerRef ref = store.getComponent(playerRef, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        String worldId = ref == null || ref.getWorldUuid() == null ? "" : ref.getWorldUuid().toString();
        info(context, "Block snapshot: " + block);
        return worldId + ":" + block.x + ":" + block.y + ":" + block.z;
    }

    private String resolveLookedTarget(CommandContext context, String scope) {
        if (scope.equals("entity")) {
            TargetEntity target = targetEntity(context);
            return target == null ? null : target.uuid();
        }
        if (scope.equals("block")) {
            return targetBlockKey(context);
        }
        return null;
    }

    private Void volume(CommandContext context, String[] args) {
        if (!requireVolumeAdmin(context)) {
            return null;
        }
        if (args.length < 2) {
            usage(context, "Usage: /mquest volume <uuid|state|tag> ...");
            return null;
        }
        switch (args[1].toLowerCase()) {
            case "uuid" -> {
                warn(context, "Trigger volume lookup is not exposed by the current Hytale API.");
                info(context, "Use configured volume keys as <worldName>:<volumeId>, or <volumeId> if world name is unavailable.");
            }
            case "state" -> volumeState(context, args);
            case "tag" -> volumeTag(context, args);
            default -> usage(context, "Usage: /mquest volume <uuid|state|tag> ...");
        }
        return null;
    }

    private void volumeState(CommandContext context, String[] args) {
        if (args.length < 4 || !args[3].equalsIgnoreCase("get")) {
            usage(context, "Usage: /mquest volume state <volume> get [key]");
            return;
        }
        String volume = args[2];
        if (args.length >= 5) {
            info(context, args[4] + " = " + runtime.scopedStateService().variables("volume", volume).getOrDefault(args[4], ""));
        } else {
            info(context, "Tags: " + runtime.scopedStateService().tags("volume", volume));
            info(context, "Variables: " + runtime.scopedStateService().variables("volume", volume));
        }
    }

    private void volumeTag(CommandContext context, String[] args) {
        if (args.length < 4) {
            usage(context, "Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]");
            return;
        }
        String volume = args[2];
        String mutation = args[3].toLowerCase();
        if (mutation.equals("list")) {
            info(context, "Tags: " + runtime.scopedStateService().tags("volume", volume));
            return;
        }
        if (args.length < 5) {
            usage(context, "Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]");
            return;
        }
        EventDefinition event = scopedEvent("volume", volume, args[4]);
        UUID actor = context.sender().getUuid();
        switch (mutation) {
            case "add" -> {
                runtime.scopedStateService().addTag(actor, event, QuestTargetContext.none());
                success(context, "Added tag " + args[4] + " to volume:" + volume);
            }
            case "remove" -> {
                runtime.scopedStateService().removeTag(actor, event, QuestTargetContext.none());
                success(context, "Removed tag " + args[4] + " from volume:" + volume);
            }
            case "has" -> info(context, Boolean.toString(runtime.scopedStateService().hasTag(actor, event, QuestTargetContext.none())));
            default -> usage(context, "Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]");
        }
    }

    private CompletableFuture<Void> hyCitizens(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.entity")) {
            return CompletableFuture.completedFuture(null);
        }
        if (runtime.hyCitizensBridge() == null || !runtime.hyCitizensBridge().enabled()) {
            warn(context, "HyCitizens integration is disabled in MysticQuests config.");
            return CompletableFuture.completedFuture(null);
        }
        if (!runtime.hyCitizensBridge().available()) {
            warn(context, "HyCitizens is not available or did not start cleanly.");
            return CompletableFuture.completedFuture(null);
        }
        if (args.length < 2) {
            usage(context, "Usage: /mquest hycitizens <list|info|bind> ...");
            return CompletableFuture.completedFuture(null);
        }
        switch (args[1].toLowerCase()) {
            case "list" -> {
                boolean near = args.length >= 3 && args[2].equalsIgnoreCase("near");
                if (!near) {
                    listHyCitizens(context, runtime.hyCitizensBridge().citizens(), null);
                    return CompletableFuture.completedFuture(null);
                }
                if (!context.isPlayer()) {
                    warn(context, "Only players can list nearby HyCitizens citizens.");
                    return CompletableFuture.completedFuture(null);
                }
                return onPlayerWorld(context, () -> {
                    org.joml.Vector3d position = playerPosition(context);
                    if (position == null) {
                        return;
                    }
                    listHyCitizens(context, runtime.hyCitizensBridge().citizensNear(position, 24.0D), position);
                });
            }
            case "info" -> {
                if (args.length < 3) {
                    usage(context, "Usage: /mquest hycitizens info <id>");
                    return CompletableFuture.completedFuture(null);
                }
                runtime.hyCitizensBridge().citizen(args[2])
                        .ifPresentOrElse(
                                citizen -> sendHyCitizenInfo(context, citizen),
                                () -> warn(context, "Unknown HyCitizens citizen: " + args[2]));
            }
            case "bind" -> {
                if (args.length < 4) {
                    usage(context, "Usage: /mquest hycitizens bind <conversation> <id>");
                    return CompletableFuture.completedFuture(null);
                }
                runtime.hyCitizensBridge().citizen(args[3])
                        .ifPresentOrElse(
                                citizen -> {
                                    info(context, "Conversation " + args[2] + " HyCitizens binding:");
                                    valueLine(context, "", hyCitizenJson(citizen), TEXT);
                                },
                                () -> warn(context, "Unknown HyCitizens citizen: " + args[3]));
            }
            default -> usage(context, "Usage: /mquest hycitizens <list|info|bind> ...");
        }
        return CompletableFuture.completedFuture(null);
    }

    private void listHyCitizens(CommandContext context, List<CitizenView> citizens, org.joml.Vector3d origin) {
        if (citizens.isEmpty()) {
            warn(context, "No HyCitizens citizens found.");
            return;
        }
        info(context, "HyCitizens citizens:");
        int limit = Math.min(citizens.size(), 20);
        for (int index = 0; index < limit; index++) {
            CitizenView citizen = citizens.get(index);
            String name = citizen.name() == null || citizen.name().isBlank() ? citizen.id() : citizen.name();
            context.sendMessage(Message.join(
                    Message.raw((index + 1) + ". ").color(GOLD),
                    Message.raw(name).color(TEXT),
                    Message.raw(" [" + citizen.id() + "]").color(MUTED)));
            String group = citizen.group() == null || citizen.group().isBlank() ? "default" : citizen.group();
            String distance = origin == null || Double.isNaN(citizen.x())
                    ? ""
                    : " distance=" + format(distance(origin, new org.joml.Vector3d(citizen.x(), citizen.y(), citizen.z())));
            info(context, "   group=" + group + " spawnedUuid=" + blankDash(citizen.spawnedUuid()) + distance);
        }
        if (citizens.size() > limit) {
            warn(context, "Showing " + limit + " of " + citizens.size() + " citizens.");
        }
    }

    private void sendHyCitizenInfo(CommandContext context, CitizenView citizen) {
        info(context, "HyCitizens citizen details:");
        valueLine(context, "ID: ", citizen.id(), GOLD);
        info(context, "Name: " + blankDash(citizen.name()));
        info(context, "Group: " + blankDash(citizen.group()));
        info(context, "Spawned UUID: " + blankDash(citizen.spawnedUuid()));
        info(context, "World UUID: " + blankDash(citizen.worldUuid()));
        if (!Double.isNaN(citizen.x())) {
            info(context, "Location: " + format(citizen.x()) + ", " + format(citizen.y()) + ", " + format(citizen.z()));
        }
        info(context, "NPC ref: " + blankDash(citizen.npcRef()));
        info(context, "Conversations: " + runtime.conversationService().conversationsFor(citizen));
        if (citizen.spawnedUuid() != null && !citizen.spawnedUuid().isBlank()) {
            Set<String> tags = runtime.scopedStateService().tags("entity", citizen.spawnedUuid());
            Map<String, String> variables = runtime.scopedStateService().variables("entity", citizen.spawnedUuid());
            info(context, "Entity tags: " + (tags.isEmpty() ? "[]" : tags));
            info(context, "Entity variables: " + (variables.isEmpty() ? "{}" : variables));
        }
        valueLine(context, "JSON: ", hyCitizenJson(citizen), TEXT);
    }

    private org.joml.Vector3d playerPosition(CommandContext context) {
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        TransformComponent transform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (transform == null || transform.getPosition() == null) {
            warn(context, "Could not read your current position.");
            return null;
        }
        return transform.getPosition();
    }

    private TargetEntity targetEntity(CommandContext context) {
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        Ref<EntityStore> targetRef;
        try {
            targetRef = TargetUtil.getTargetEntity(playerRef, 8.0F, store);
        } catch (RuntimeException exception) {
            warn(context, "Line-of-sight entity lookup failed: " + rootMessage(exception));
            usage(context, "Try /mquest entity scan 5 to list nearby non-player entities.");
            return null;
        }
        if (targetRef == null || !targetRef.isValid()) {
            warn(context, "No entity found in your line of sight.");
            return null;
        }
        if (store.getComponent(targetRef, Player.getComponentType()) != null) {
            warn(context, "Target is a player; MysticQuests entity binding only supports non-player entities.");
            return null;
        }
        Entity entity = entityComponent(store, targetRef);
        if (entity == null) {
            warn(context, "Target entity could not be resolved to a Hytale Entity component.");
            return null;
        }
        String name = entity.getLegacyDisplayName() == null ? "" : entity.getLegacyDisplayName();
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        TransformComponent targetTransform = store.getComponent(targetRef, TransformComponent.getComponentType());
        double distance = -1.0D;
        double x = 0.0D;
        double y = 0.0D;
        double z = 0.0D;
        if (targetTransform != null && targetTransform.getPosition() != null) {
            org.joml.Vector3d position = targetTransform.getPosition();
            x = position.x;
            y = position.y;
            z = position.z;
            if (playerTransform != null && playerTransform.getPosition() != null) {
                distance = distance(playerTransform.getPosition(), position);
            }
        }
        boolean interactable = store.getComponent(targetRef, Interactable.getComponentType()) != null;
        return new TargetEntity(
                normalizeUuid(entity.getUuid().toString()),
                name,
                entity.getClass().getSimpleName(),
                distance,
                worldId(store, playerRef),
                x,
                y,
                z,
                interactable);
    }

    private double parseRadius(String token, double fallback) {
        try {
            return Math.max(1.0D, Math.min(32.0D, Double.parseDouble(token)));
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private void scanEntities(CommandContext context, double radius) {
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        TransformComponent playerTransform = store.getComponent(playerRef, TransformComponent.getComponentType());
        if (playerTransform == null || playerTransform.getPosition() == null) {
            warn(context, "Could not read your current position.");
            return;
        }
        org.joml.Vector3d playerPosition = playerTransform.getPosition();
        String worldId = worldId(store, playerRef);
        List<NearbyEntity> nearby = new ArrayList<>();
        store.forEachChunk((BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>)
                (chunk, buffer) -> scanChunk(playerRef, playerPosition, radius, worldId, chunk, nearby));
        nearby.sort(Comparator.comparingDouble(NearbyEntity::distance));
        if (nearby.isEmpty()) {
            warn(context, "No non-player entities found within " + format(radius) + " blocks.");
            return;
        }
        info(context, "Nearby non-player entities within " + format(radius) + " blocks:");
        int limit = Math.min(nearby.size(), 12);
        for (int index = 0; index < limit; index++) {
            NearbyEntity entity = nearby.get(index);
            context.sendMessage(Message.join(
                    Message.raw((index + 1) + ". ").color(GOLD),
                    Message.raw(entity.name().isBlank() ? "(no display name)" : entity.name()).color(TEXT),
                    Message.raw(" [" + entity.type() + "] ").color(MUTED),
                    Message.raw(format(entity.distance()) + " blocks").color(BLUE)));
            valueLine(context, "   UUID: ", entity.uuid(), GOLD);
            info(context, "   Location: " + entity.worldId() + " @ "
                    + format(entity.x()) + ", " + format(entity.y()) + ", " + format(entity.z()));
            Map<String, String> variables = runtime.scopedStateService().variables("entity", entity.uuid());
            Set<String> tags = runtime.scopedStateService().tags("entity", entity.uuid());
            info(context, "   Tags: " + (tags.isEmpty() ? "[]" : tags));
            info(context, "   Variables: " + (variables.isEmpty() ? "{}" : variables));
            String json = entityJson(entity.uuid(), entity.type(), entity.name());
            context.sendMessage(Message.join(
                    Message.raw("   JSON: ").color(MUTED),
                    Message.raw(json).color(TEXT)));
        }
        if (nearby.size() > limit) {
            warn(context, "Showing " + limit + " of " + nearby.size() + " entities. Use a smaller radius to narrow it down.");
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private void scanChunk(
            Ref<EntityStore> playerRef,
            org.joml.Vector3d playerPosition,
            double radius,
            String worldId,
            ArchetypeChunk<EntityStore> chunk,
            List<NearbyEntity> nearby) {
        Archetype<EntityStore> archetype = chunk.getArchetype();
        if (!archetype.contains(TransformComponent.getComponentType())) {
            return;
        }
        for (int index = 0; index < chunk.size(); index++) {
            Ref<EntityStore> ref = chunk.getReferenceTo(index);
            if (ref == null || !ref.isValid() || ref.equals(playerRef)) {
                continue;
            }
            if (chunk.getComponent(index, Player.getComponentType()) != null) {
                continue;
            }
            TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
            if (transform == null || transform.getPosition() == null) {
                continue;
            }
            org.joml.Vector3d position = transform.getPosition();
            double distance = distance(playerPosition, position);
            if (distance > radius) {
                continue;
            }
            Entity entity = entityComponent(chunk, index);
            if (entity == null || entity.getUuid() == null) {
                continue;
            }
            String name = entity.getLegacyDisplayName() == null ? "" : entity.getLegacyDisplayName();
            nearby.add(new NearbyEntity(
                    normalizeUuid(entity.getUuid().toString()),
                    name,
                    entity.getClass().getSimpleName(),
                    distance,
                    worldId,
                    position.x,
                    position.y,
                    position.z));
        }
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity entityComponent(Store<EntityStore> store, Ref<EntityStore> targetRef) {
        Archetype<EntityStore> archetype = store.getArchetype(targetRef);
        for (int index = 0; index < archetype.length(); index++) {
            ComponentType componentType = archetype.get(index);
            if (componentType == null || componentType.getTypeClass() == null) {
                continue;
            }
            if (Entity.class.isAssignableFrom(componentType.getTypeClass())) {
                Component component = store.getComponent(targetRef, componentType);
                if (component instanceof Entity entity) {
                    return entity;
                }
            }
        }
        return null;
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity entityComponent(ArchetypeChunk<EntityStore> chunk, int entityIndex) {
        Archetype<EntityStore> archetype = chunk.getArchetype();
        for (int index = 0; index < archetype.length(); index++) {
            ComponentType componentType = archetype.get(index);
            if (componentType == null || componentType.getTypeClass() == null) {
                continue;
            }
            if (Entity.class.isAssignableFrom(componentType.getTypeClass())) {
                Component component = chunk.getComponent(entityIndex, componentType);
                if (component instanceof Entity entity) {
                    return entity;
                }
            }
        }
        return null;
    }

    private double distance(org.joml.Vector3d from, org.joml.Vector3d to) {
        double dx = from.x - to.x;
        double dy = from.y - to.y;
        double dz = from.z - to.z;
        return Math.sqrt(dx * dx + dy * dy + dz * dz);
    }

    private String worldId(Store<EntityStore> store, Ref<EntityStore> playerRef) {
        com.hypixel.hytale.server.core.universe.PlayerRef ref = store.getComponent(playerRef, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        return ref == null || ref.getWorldUuid() == null ? "world" : ref.getWorldUuid().toString();
    }

    private String format(double value) {
        return String.format(Locale.US, "%.2f", value);
    }

    private String entityJson(String uuid, String type, String name) {
        return "\"entity\": { \"uuid\": \"" + escapeJson(uuid)
                + "\", \"type\": \"" + escapeJson(type)
                + "\", \"name\": \"" + escapeJson(name) + "\" }";
    }

    private String hyCitizenJson(CitizenView citizen) {
        return "\"entity\": { \"hyCitizensId\": \"" + escapeJson(citizen.id())
                + "\", \"hyCitizensGroup\": \"" + escapeJson(citizen.group())
                + "\", \"uuid\": \"" + escapeJson(citizen.spawnedUuid())
                + "\", \"type\": \"HyCitizens\", \"name\": \"" + escapeJson(citizen.name())
                + "\", \"interactionHint\": \"talk\" }";
    }

    private String blankDash(String value) {
        return value == null || value.isBlank() ? "-" : value;
    }

    private String escapeJson(String value) {
        return value == null ? "" : value.replace("\\", "\\\\").replace("\"", "\\\"");
    }

    private void valueLine(CommandContext context, String label, String value, String color) {
        context.sendMessage(Message.join(
                Message.raw(label).color(MUTED),
                Message.raw(value).color(color)));
    }

    private String normalizeUuid(String value) {
        if (value == null) {
            return "";
        }
        String trimmed = value.trim();
        if (trimmed.length() % 2 == 0) {
            String first = trimmed.substring(0, trimmed.length() / 2);
            String second = trimmed.substring(trimmed.length() / 2);
            if (first.equals(second)) {
                return first;
            }
        }
        java.util.regex.Matcher matcher = java.util.regex.Pattern
                .compile("[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}")
                .matcher(trimmed);
        return matcher.find() ? matcher.group() : trimmed;
    }

    private List<String> conversationsFor(TargetEntity target) {
        return runtime.content().conversations().values().stream()
                .filter(conversation -> conversation.entity() != null)
                .filter(conversation -> {
                    var binding = conversation.entity();
                    boolean uuidMatches = binding.uuid() != null && binding.uuid().equalsIgnoreCase(target.uuid());
                    boolean typeMatches = binding.type() != null
                            && (binding.type().equalsIgnoreCase(target.type()) || binding.type().equalsIgnoreCase("NPCEntity") && target.type().equalsIgnoreCase("NPCEntity"));
                    boolean nameMatches = binding.name() != null && binding.name().equalsIgnoreCase(target.name());
                    return uuidMatches || typeMatches || nameMatches;
                })
                .map(conversation -> conversation.packageId() + ":" + conversation.id())
                .toList();
    }

    private String rootMessage(Throwable throwable) {
        Throwable current = throwable;
        while (current.getCause() != null) {
            current = current.getCause();
        }
        return current.getMessage() == null ? current.getClass().getSimpleName() : current.getMessage();
    }

    private record TargetEntity(
            String uuid,
            String name,
            String type,
            double distance,
            String worldId,
            double x,
            double y,
            double z,
            boolean interactable) {
    }

    private record NearbyEntity(
            String uuid,
            String name,
            String type,
            double distance,
            String worldId,
            double x,
            double y,
            double z) {
    }

    private boolean requireAdmin(CommandContext context, String permission) {
        if (context.sender().hasPermission("mysticquests.admin") || context.sender().hasPermission(permission)) {
            return true;
        }
        error(context, "Missing permission: " + permission);
        return false;
    }

    private boolean requireVolumeAdmin(CommandContext context) {
        if (context.sender().hasPermission("mysticquests.admin")
                || context.sender().hasPermission("mysticquests.command.admin.volume")
                || context.sender().hasPermission("mysticquests.command.admin.entity")) {
            return true;
        }
        error(context, "Missing permission: mysticquests.command.admin.volume");
        return false;
    }

    private boolean requirePermission(CommandContext context, String permission) {
        if (context.sender().hasPermission("mysticquests.admin") || context.sender().hasPermission(permission)) {
            return true;
        }
        error(context, "Missing permission: " + permission);
        return false;
    }

    private String targetToken(CommandContext context, String scope, String token) {
        if (scope.equals("global")) {
            return ScopedStateService.GLOBAL_OWNER;
        }
        if (token.equalsIgnoreCase("self")) {
            return context.sender().getUuid().toString();
        }
        if (scope.equals("player")) {
            return runtime.resolveOnlinePlayer(token)
                    .map(UUID::toString)
                    .orElse(token);
        }
        return token;
    }

    private EventDefinition scopedEvent(String scope, String target, String tag) {
        EventDefinition event = new EventDefinition();
        event.setType("addTag");
        event.put("scope", TextNode.valueOf(scope));
        event.put("target", TextNode.valueOf(target));
        event.put("tag", TextNode.valueOf(tag));
        return event;
    }

    private CompletableFuture<Void> onPlayerWorld(CommandContext context, Runnable action) {
        CompletableFuture<Void> future = new CompletableFuture<>();
        try {
            Ref<EntityStore> playerRef = context.senderAsPlayerRef();
            Store<EntityStore> store = playerRef.getStore();
            World world = store.getExternalData().getWorld();
            Runnable guarded = () -> {
                try {
                    action.run();
                    future.complete(null);
                } catch (Throwable throwable) {
                    error(context, "MysticQuests command failed: " + rootMessage(throwable));
                    future.complete(null);
                }
            };
            if (store.isInThread()) {
                guarded.run();
            } else {
                world.execute(guarded);
            }
        } catch (Throwable throwable) {
            error(context, "MysticQuests command failed: " + rootMessage(throwable));
            future.complete(null);
        }
        return future;
    }

    private void result(CommandContext context, QuestResult result) {
        if (result.success()) {
            success(context, result.message());
        } else {
            error(context, result.message());
        }
    }

    private void success(CommandContext context, String text) {
        send(context, text, GREEN);
    }

    private void info(CommandContext context, String text) {
        send(context, text, TEXT);
    }

    private void warn(CommandContext context, String text) {
        send(context, text, ORANGE);
    }

    private void error(CommandContext context, String text) {
        send(context, text, RED);
    }

    private void usage(CommandContext context, String text) {
        send(context, text, BLUE);
    }

    private void send(CommandContext context, String text, String color) {
        context.sendMessage(Message.raw(text).color(color));
    }

    private final class RouteCommand extends AbstractCommand {
        private RouteCommand(String name) {
            super(name, "MysticQuests " + name);
            setAllowsExtraArguments(true);
        }

        @Override
        protected CompletableFuture<Void> execute(CommandContext context) {
            return MQuestCommand.this.execute(context);
        }
    }

    private static final class SuggestedStringArgument extends SingleArgumentType<String> {
        private final Supplier<Collection<String>> values;

        private SuggestedStringArgument(String name, Supplier<Collection<String>> values) {
            super(name, name);
            this.values = values;
        }

        @Override
        public String parse(String input, ParseResult result) {
            return input;
        }

        @Override
        public void suggest(CommandSender sender, String current, int parameterIndex, SuggestionResult result) {
            String lower = current == null ? "" : current.toLowerCase();
            Set<String> suggestions = new LinkedHashSet<>();
            for (String value : values.get()) {
                if (value == null || value.isBlank()) {
                    continue;
                }
                if (lower.isBlank() || value.toLowerCase().startsWith(lower)) {
                    suggestions.add(value);
                }
            }
            for (String suggestion : suggestions) {
                result.suggest(suggestion);
            }
        }
    }
}
