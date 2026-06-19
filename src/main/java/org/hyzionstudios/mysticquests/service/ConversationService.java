package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConversationChoice;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.ConversationEntityBinding;
import org.hyzionstudios.mysticquests.model.ConversationNode;
import org.hyzionstudios.mysticquests.ui.ConversationPage;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ConversationService {
    private final Supplier<LoadedContent> contentSupplier;
    private final PlayerQuestService questService;
    private final QuestSignalBus signalBus;
    private final HytaleLogger logger;
    private final Map<UUID, ConversationSession> sessions = new ConcurrentHashMap<>();

    public ConversationService(
            Supplier<LoadedContent> contentSupplier,
            PlayerQuestService questService,
            QuestSignalBus signalBus,
            HytaleLogger logger) {
        this.contentSupplier = contentSupplier;
        this.questService = questService;
        this.signalBus = signalBus;
        this.logger = logger;
    }

    public boolean tryStart(PlayerInteractEvent event) {
        Entity target = event.getTargetEntity();
        if (target == null || target instanceof Player) {
            return false;
        }
        PlayerRef playerRef = event.getPlayer().getPlayerRef();
        if (playerRef == null) {
            return false;
        }
        ConversationDefinition conversation = findConversation(target);
        if (conversation == null) {
            return false;
        }
        ConversationNode node = node(conversation, conversation.start());
        QuestTargetContext targetContext = entityContext(target);
        if (node == null || !conditionsPass(playerRef.getUuid(), conversation, node.conditions(), targetContext)) {
            return false;
        }
        sessions.put(playerRef.getUuid(), new ConversationSession(playerRef.getUuid(), conversation.packageId() + ":" + conversation.id(), node.id(), targetContext));
        enterNode(playerRef.getUuid(), conversation, node, targetContext);
        openPage(event.getPlayerRef(), event.getPlayerRef().getStore(), event.getPlayer(), playerRef);
        return true;
    }

    public ConversationView view(UUID playerId) {
        ConversationSession session = sessions.get(playerId);
        if (session == null) {
            return null;
        }
        ConversationDefinition conversation = contentSupplier.get().conversations().get(session.conversationId());
        if (conversation == null) {
            sessions.remove(playerId);
            return null;
        }
        ConversationNode node = node(conversation, session.nodeId());
        if (node == null) {
            sessions.remove(playerId);
            return null;
        }
        List<ConversationChoice> choices = visibleChoices(playerId, conversation, node, session.targetContext());
        return new ConversationView(conversation.speaker(), conversation.id(), node.text(), choices);
    }

    public boolean choose(UUID playerId, int choiceIndex, Ref<EntityStore> playerEntity, Store<EntityStore> store) {
        ConversationSession session = sessions.get(playerId);
        if (session == null) {
            return false;
        }
        ConversationDefinition conversation = contentSupplier.get().conversations().get(session.conversationId());
        ConversationNode node = conversation == null ? null : node(conversation, session.nodeId());
        if (conversation == null || node == null) {
            end(playerId, playerEntity, store);
            return false;
        }
        List<ConversationChoice> choices = visibleChoices(playerId, conversation, node, session.targetContext());
        if (choiceIndex < 0 || choiceIndex >= choices.size()) {
            return true;
        }
        ConversationChoice choice = choices.get(choiceIndex);
        questService.executeEvents(playerId, conversation.packageId(), choice.events(), session.targetContext());
        String next = choice.next();
        if (next == null || next.isBlank() || next.equalsIgnoreCase("end")) {
            signalBus.publish(QuestSignal.simple(playerId, "dialogue", session.conversationId(), 1));
            end(playerId, playerEntity, store);
            return false;
        }
        ConversationNode nextNode = node(conversation, next);
        if (nextNode == null || !conditionsPass(playerId, conversation, nextNode.conditions(), session.targetContext())) {
            end(playerId, playerEntity, store);
            return false;
        }
        sessions.put(playerId, new ConversationSession(playerId, session.conversationId(), nextNode.id(), session.targetContext()));
        enterNode(playerId, conversation, nextNode, session.targetContext());
        return true;
    }

    public void end(UUID playerId, Ref<EntityStore> playerEntity, Store<EntityStore> store) {
        sessions.remove(playerId);
        Player player = store.getComponent(playerEntity, Player.getComponentType());
        if (player != null) {
            player.getPageManager().setPage(playerEntity, store, Page.None);
        }
    }

    private ConversationDefinition findConversation(Entity target) {
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> matches(conversation.entity(), target))
                .findFirst()
                .orElse(null);
    }

    private boolean matches(ConversationEntityBinding binding, Entity target) {
        if (binding == null || target == null) {
            return false;
        }
        UUID targetUuid = target.getUuid();
        if (binding.uuid() != null && targetUuid != null && binding.uuid().equalsIgnoreCase(targetUuid.toString())) {
            return true;
        }
        String type = target.getClass().getSimpleName();
        String fullType = target.getClass().getName();
        String name = target.getLegacyDisplayName();
        boolean typeMatches = binding.type() != null
                && (binding.type().equalsIgnoreCase(type) || binding.type().equalsIgnoreCase(fullType));
        boolean nameMatches = binding.name() != null && binding.name().equalsIgnoreCase(Objects.toString(name, ""));
        return typeMatches || nameMatches;
    }

    private void openPage(Ref<EntityStore> playerEntity, Store<EntityStore> store, Player player, PlayerRef playerRef) {
        try {
            player.getPageManager().openCustomPage(playerEntity, store, new ConversationPage(playerRef, playerRef.getUuid(), this));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to open MysticQuests conversation page.");
        }
    }

    private void enterNode(UUID playerId, ConversationDefinition conversation, ConversationNode node, QuestTargetContext targetContext) {
        questService.executeEvents(playerId, conversation.packageId(), node.events(), targetContext);
        String conversationId = conversation.packageId() + ":" + conversation.id();
        signalBus.publish(QuestSignal.simple(playerId, "dialogue", conversationId + ":" + node.id(), 1));
    }

    private List<ConversationChoice> visibleChoices(UUID playerId, ConversationDefinition conversation, ConversationNode node, QuestTargetContext targetContext) {
        List<ConversationChoice> choices = new ArrayList<>();
        for (ConversationChoice choice : node.choices()) {
            if (conditionsPass(playerId, conversation, choice.conditions(), targetContext)) {
                choices.add(choice);
            }
        }
        return choices;
    }

    private boolean conditionsPass(UUID playerId, ConversationDefinition conversation, List<? extends org.hyzionstudios.mysticquests.model.ConditionDefinition> conditions, QuestTargetContext targetContext) {
        for (org.hyzionstudios.mysticquests.model.ConditionDefinition condition : conditions) {
            if (!questService.evaluateCondition(playerId, conversation.packageId(), condition, targetContext)) {
                return false;
            }
        }
        return true;
    }

    private ConversationNode node(ConversationDefinition conversation, String nodeId) {
        if (nodeId == null || nodeId.isBlank()) {
            return null;
        }
        for (ConversationNode node : conversation.nodes()) {
            if (node.id().equals(nodeId)) {
                return node;
            }
        }
        return null;
    }

    private QuestTargetContext entityContext(Entity target) {
        return new QuestTargetContext(
                target.getUuid() == null ? null : target.getUuid().toString(),
                target.getClass().getSimpleName(),
                target.getLegacyDisplayName(),
                null,
                null,
                "",
                null,
                null,
                null);
    }

    private record ConversationSession(UUID playerId, String conversationId, String nodeId, QuestTargetContext targetContext) {
    }

    public record ConversationView(String speaker, String conversationId, String text, List<ConversationChoice> choices) {
    }
}
