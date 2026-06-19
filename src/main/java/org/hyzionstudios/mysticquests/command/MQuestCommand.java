package org.hyzionstudios.mysticquests.command;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.QuestResult;

import com.fasterxml.jackson.databind.node.TextNode;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.command.system.AbstractCommand;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.TargetUtil;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;

import java.io.IOException;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;

public final class MQuestCommand extends AbstractCommand {
    private final MysticQuestsRuntime runtime;

    public MQuestCommand(MysticQuestsRuntime runtime) {
        super("mquest", "MysticQuests administration and journal commands");
        this.runtime = runtime;
        setAllowsExtraArguments(true);
    }

    @Override
    protected CompletableFuture<Void> execute(CommandContext context) {
        String[] args = parseArgs(context.getInputString());
        if (args.length == 0) {
            sendHelp(context);
            return CompletableFuture.completedFuture(null);
        }
        switch (args[0].toLowerCase()) {
            case "reload" -> reload(context);
            case "start" -> start(context, args);
            case "complete" -> complete(context, args);
            case "progress" -> journal(context, args, false);
            case "journal" -> journal(context, args, true);
            case "track" -> track(context, args);
            case "untrack" -> untrack(context);
            case "entity" -> entity(context, args);
            case "state" -> state(context, args);
            case "block" -> block(context, args);
            case "volume" -> volume(context, args);
            case "debug" -> debug(context, args);
            default -> sendHelp(context);
        }
        return CompletableFuture.completedFuture(null);
    }

    private String[] parseArgs(String input) {
        if (input == null || input.isBlank()) {
            return new String[0];
        }
        String[] raw = input.trim().split("\\s+");
        if (raw.length > 0 && raw[0].equalsIgnoreCase("mquest")) {
            return Arrays.copyOfRange(raw, 1, raw.length);
        }
        return raw;
    }

    private void reload(CommandContext context) {
        if (!requireAdmin(context, "mysticquests.command.admin.reload")) {
            return;
        }
        try {
            int count = runtime.reloadContent().quests().size();
            context.sendMessage(Message.raw("MysticQuests reloaded " + count + " quests."));
        } catch (IOException exception) {
            context.sendMessage(Message.raw("MysticQuests reload failed: " + exception.getMessage()));
        }
    }

    private void start(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return;
        }
        if (args.length < 3) {
            context.sendMessage(Message.raw("Usage: /mquest start <player-uuid|self> <quest>"));
            return;
        }
        UUID playerId = playerId(context, args[1]);
        QuestResult result = runtime.questService().startQuest(playerId, args[2]);
        context.sendMessage(Message.raw(result.message()));
    }

    private void complete(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.quest")) {
            return;
        }
        if (args.length < 3) {
            context.sendMessage(Message.raw("Usage: /mquest complete <player-uuid|self> <quest>"));
            return;
        }
        UUID playerId = playerId(context, args[1]);
        QuestResult result = runtime.questService().completeQuest(playerId, args[2]);
        context.sendMessage(Message.raw(result.message()));
    }

    private void journal(CommandContext context, String[] args, boolean openUi) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return;
        }
        if (openUi && context.isPlayer() && (args.length < 2 || args[1].equalsIgnoreCase("self"))) {
            if (runtime.uiService().openJournal(context)) {
                return;
            }
        }
        UUID playerId = args.length >= 2 ? playerId(context, args[1]) : context.sender().getUuid();
        List<JournalEntry> entries = runtime.questService().journal(playerId);
        if (entries.isEmpty()) {
            context.sendMessage(Message.raw("No active quests."));
            return;
        }
        for (JournalEntry entry : entries) {
            context.sendMessage(Message.raw(entry.displayName() + " (" + entry.questId() + ")"));
            for (String objective : entry.objectives()) {
                context.sendMessage(Message.raw(" - " + objective));
            }
        }
    }

    private void track(CommandContext context, String[] args) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return;
        }
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Only players can track quests."));
            return;
        }
        if (args.length < 2) {
            context.sendMessage(Message.raw("Usage: /mquest track <quest>"));
            return;
        }
        QuestResult result = runtime.questService().trackQuest(context.sender().getUuid(), args[1]);
        context.sendMessage(Message.raw(result.message()));
    }

    private void untrack(CommandContext context) {
        if (!requirePermission(context, "mysticquests.command.journal")) {
            return;
        }
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Only players can untrack quests."));
            return;
        }
        QuestResult result = runtime.questService().untrackQuest(context.sender().getUuid());
        context.sendMessage(Message.raw(result.message()));
    }

    private void entity(CommandContext context, String[] args) {
        if (args.length < 2) {
            context.sendMessage(Message.raw("Usage: /mquest entity <uuid|bind> [conversation]"));
            return;
        }
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Only players can target entities."));
            return;
        }
        if (!requireAdmin(context, "mysticquests.command.admin.entity")) {
            return;
        }
        TargetEntity target = targetEntity(context);
        if (target == null) {
            return;
        }
        switch (args[1].toLowerCase()) {
            case "uuid" -> {
                context.sendMessage(Message.raw("Entity UUID: " + target.uuid()));
                context.sendMessage(Message.raw("Entity name: " + target.name()));
                context.sendMessage(Message.raw("Entity type: " + target.type()));
            }
            case "bind" -> {
                if (args.length < 3) {
                    context.sendMessage(Message.raw("Usage: /mquest entity bind <conversation>"));
                    return;
                }
                context.sendMessage(Message.raw("Conversation " + args[2] + " entity binding:"));
                context.sendMessage(Message.raw("\"entity\": { \"uuid\": \"" + target.uuid()
                        + "\", \"type\": \"" + target.type()
                        + "\", \"name\": \"" + target.name() + "\" }"));
            }
            default -> context.sendMessage(Message.raw("Usage: /mquest entity <uuid|bind> [conversation]"));
        }
    }

    private void debug(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.debug")) {
            return;
        }
        if (args.length < 2) {
            context.sendMessage(Message.raw("Loaded quests: " + runtime.content().quests().keySet()));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "package" -> context.sendMessage(Message.raw("Loaded packages: " + runtime.content().packages()));
            case "quest" -> context.sendMessage(Message.raw("Loaded quests: " + runtime.content().quests().keySet()));
            case "player" -> {
                UUID playerId = args.length >= 3 ? playerId(context, args[2]) : context.sender().getUuid();
                context.sendMessage(Message.raw("Active journal entries: " + runtime.questService().journal(playerId).size()));
            }
            default -> context.sendMessage(Message.raw("Unknown debug target."));
        }
    }

    private UUID playerId(CommandContext context, String token) {
        if (token.equalsIgnoreCase("self")) {
            return context.sender().getUuid();
        }
        return UUID.fromString(token);
    }

    private void sendHelp(CommandContext context) {
        context.sendMessage(Message.raw("MysticQuests commands: reload, start, complete, progress, journal, track, untrack, entity, state, block, volume, debug"));
    }

    private void state(CommandContext context, String[] args) {
        if (!requireAdmin(context, "mysticquests.command.admin.debug")) {
            return;
        }
        if (args.length < 4) {
            context.sendMessage(Message.raw("Usage: /mquest state <get|tag> <scope> <target> ..."));
            return;
        }
        String action = args[1].toLowerCase();
        String scope = ScopedStateService.normalizeScope(args[2]);
        String target = targetToken(context, scope, args[3]);
        if (action.equals("get")) {
            if (args.length >= 5) {
                context.sendMessage(Message.raw(args[4] + " = " + runtime.scopedStateService().variables(scope, target).getOrDefault(args[4], "")));
            } else {
                context.sendMessage(Message.raw("Tags: " + runtime.scopedStateService().tags(scope, target)));
                context.sendMessage(Message.raw("Variables: " + runtime.scopedStateService().variables(scope, target)));
            }
            return;
        }
        if (!action.equals("tag") || args.length < 5) {
            context.sendMessage(Message.raw("Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]"));
            return;
        }
        String mutation = args[4].toLowerCase();
        if (mutation.equals("list")) {
            context.sendMessage(Message.raw("Tags: " + runtime.scopedStateService().tags(scope, target)));
            return;
        }
        if (args.length < 6) {
            context.sendMessage(Message.raw("Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]"));
            return;
        }
        EventDefinition event = scopedEvent(scope, target, args[5]);
        UUID actor = context.sender().getUuid();
        switch (mutation) {
            case "add" -> {
                runtime.scopedStateService().addTag(actor, event, QuestTargetContext.none());
                context.sendMessage(Message.raw("Added tag " + args[5] + " to " + scope + ":" + target));
            }
            case "remove" -> {
                runtime.scopedStateService().removeTag(actor, event, QuestTargetContext.none());
                context.sendMessage(Message.raw("Removed tag " + args[5] + " from " + scope + ":" + target));
            }
            case "has" -> context.sendMessage(Message.raw(Boolean.toString(runtime.scopedStateService().hasTag(actor, event, QuestTargetContext.none()))));
            default -> context.sendMessage(Message.raw("Usage: /mquest state tag <scope> <target> <add|remove|has|list> [tag]"));
        }
    }

    private void block(CommandContext context, String[] args) {
        if (args.length < 2 || !args[1].equalsIgnoreCase("uuid")) {
            context.sendMessage(Message.raw("Usage: /mquest block uuid"));
            return;
        }
        if (!requireAdmin(context, "mysticquests.command.admin.entity")) {
            return;
        }
        if (!context.isPlayer()) {
            context.sendMessage(Message.raw("Only players can target blocks."));
            return;
        }
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        org.joml.Vector3i block = TargetUtil.getTargetBlock(playerRef, 8.0D, store);
        if (block == null) {
            context.sendMessage(Message.raw("No block found in your line of sight."));
            return;
        }
        com.hypixel.hytale.server.core.universe.PlayerRef ref = store.getComponent(playerRef, com.hypixel.hytale.server.core.universe.PlayerRef.getComponentType());
        String worldId = ref == null || ref.getWorldUuid() == null ? "" : ref.getWorldUuid().toString();
        context.sendMessage(Message.raw("Block key: " + worldId + ":" + block.x + ":" + block.y + ":" + block.z));
        context.sendMessage(Message.raw("Block snapshot: " + block));
    }

    private void volume(CommandContext context, String[] args) {
        if (!requireVolumeAdmin(context)) {
            return;
        }
        if (args.length < 2) {
            context.sendMessage(Message.raw("Usage: /mquest volume <uuid|state|tag> ..."));
            return;
        }
        switch (args[1].toLowerCase()) {
            case "uuid" -> {
                context.sendMessage(Message.raw("Trigger volume lookup is not exposed by the current Hytale API."));
                context.sendMessage(Message.raw("Use configured volume keys as <worldName>:<volumeId>, or <volumeId> if world name is unavailable."));
            }
            case "state" -> volumeState(context, args);
            case "tag" -> volumeTag(context, args);
            default -> context.sendMessage(Message.raw("Usage: /mquest volume <uuid|state|tag> ..."));
        }
    }

    private void volumeState(CommandContext context, String[] args) {
        if (args.length < 4 || !args[3].equalsIgnoreCase("get")) {
            context.sendMessage(Message.raw("Usage: /mquest volume state <volume> get [key]"));
            return;
        }
        String volume = args[2];
        if (args.length >= 5) {
            context.sendMessage(Message.raw(args[4] + " = " + runtime.scopedStateService().variables("volume", volume).getOrDefault(args[4], "")));
        } else {
            context.sendMessage(Message.raw("Tags: " + runtime.scopedStateService().tags("volume", volume)));
            context.sendMessage(Message.raw("Variables: " + runtime.scopedStateService().variables("volume", volume)));
        }
    }

    private void volumeTag(CommandContext context, String[] args) {
        if (args.length < 4) {
            context.sendMessage(Message.raw("Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]"));
            return;
        }
        String volume = args[2];
        String mutation = args[3].toLowerCase();
        if (mutation.equals("list")) {
            context.sendMessage(Message.raw("Tags: " + runtime.scopedStateService().tags("volume", volume)));
            return;
        }
        if (args.length < 5) {
            context.sendMessage(Message.raw("Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]"));
            return;
        }
        EventDefinition event = scopedEvent("volume", volume, args[4]);
        UUID actor = context.sender().getUuid();
        switch (mutation) {
            case "add" -> {
                runtime.scopedStateService().addTag(actor, event, QuestTargetContext.none());
                context.sendMessage(Message.raw("Added tag " + args[4] + " to volume:" + volume));
            }
            case "remove" -> {
                runtime.scopedStateService().removeTag(actor, event, QuestTargetContext.none());
                context.sendMessage(Message.raw("Removed tag " + args[4] + " from volume:" + volume));
            }
            case "has" -> context.sendMessage(Message.raw(Boolean.toString(runtime.scopedStateService().hasTag(actor, event, QuestTargetContext.none()))));
            default -> context.sendMessage(Message.raw("Usage: /mquest volume tag <volume> <add|remove|has|list> [tag]"));
        }
    }

    private TargetEntity targetEntity(CommandContext context) {
        Ref<EntityStore> playerRef = context.senderAsPlayerRef();
        Store<EntityStore> store = playerRef.getStore();
        Ref<EntityStore> targetRef = TargetUtil.getTargetEntity(playerRef, 8.0F, store);
        if (targetRef == null || !targetRef.isValid()) {
            context.sendMessage(Message.raw("No entity found in your line of sight."));
            return null;
        }
        if (store.getComponent(targetRef, Player.getComponentType()) != null) {
            context.sendMessage(Message.raw("Target is a player; MysticQuests entity binding only supports non-player entities."));
            return null;
        }
        Entity entity = entityComponent(store, targetRef);
        if (entity == null) {
            context.sendMessage(Message.raw("Target entity could not be resolved to a Hytale Entity component."));
            return null;
        }
        String name = entity.getLegacyDisplayName() == null ? "" : entity.getLegacyDisplayName();
        return new TargetEntity(entity.getUuid().toString(), name, entity.getClass().getSimpleName());
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity entityComponent(Store<EntityStore> store, Ref<EntityStore> targetRef) {
        Archetype<EntityStore> archetype = store.getArchetype(targetRef);
        for (int index = 0; index < archetype.length(); index++) {
            ComponentType componentType = archetype.get(index);
            if (Entity.class.isAssignableFrom(componentType.getTypeClass())) {
                Component component = store.getComponent(targetRef, componentType);
                if (component instanceof Entity entity) {
                    return entity;
                }
            }
        }
        return null;
    }

    private record TargetEntity(String uuid, String name, String type) {
    }

    private boolean requireAdmin(CommandContext context, String permission) {
        if (context.sender().hasPermission("mysticquests.admin") || context.sender().hasPermission(permission)) {
            return true;
        }
        context.sendMessage(Message.raw("Missing permission: " + permission));
        return false;
    }

    private boolean requireVolumeAdmin(CommandContext context) {
        if (context.sender().hasPermission("mysticquests.admin")
                || context.sender().hasPermission("mysticquests.command.admin.volume")
                || context.sender().hasPermission("mysticquests.command.admin.entity")) {
            return true;
        }
        context.sendMessage(Message.raw("Missing permission: mysticquests.command.admin.volume"));
        return false;
    }

    private boolean requirePermission(CommandContext context, String permission) {
        if (context.sender().hasPermission("mysticquests.admin") || context.sender().hasPermission(permission)) {
            return true;
        }
        context.sendMessage(Message.raw("Missing permission: " + permission));
        return false;
    }

    private String targetToken(CommandContext context, String scope, String token) {
        if (scope.equals("global")) {
            return ScopedStateService.GLOBAL_OWNER;
        }
        if (token.equalsIgnoreCase("self")) {
            return context.sender().getUuid().toString();
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
}
