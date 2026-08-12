package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConversationChoice;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.ConversationEntityBinding;
import org.hyzionstudios.mysticquests.model.ConversationNode;
import org.hyzionstudios.mysticquests.integration.HyCitizensBridge.CitizenView;
import org.hyzionstudios.mysticquests.ui.ConversationPage;
import org.hyzionstudios.mysticquests.ui.UiDocuments;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Component;
import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.ComponentType;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.InteractionType;
import com.hypixel.hytale.protocol.packets.interface_.Page;
import com.hypixel.hytale.protocol.InteractableUpdate;
import com.hypixel.hytale.server.core.entity.InteractionContext;
import com.hypixel.hytale.server.core.entity.Entity;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.event.events.player.PlayerInteractEvent;
import com.hypixel.hytale.server.core.event.events.player.PlayerMouseButtonEvent;
import com.hypixel.hytale.server.core.modules.entity.component.Interactable;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.modules.interaction.Interactions;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction;
import com.hypixel.hytale.server.core.modules.interaction.interaction.config.server.OpenCustomUIInteraction.CustomPageSupplier;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.protocol.MouseButtonState;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.BiConsumer;
import java.util.function.Supplier;
import java.util.logging.Level;

public final class ConversationService {
    public static final String CONVERSATION_ROOT_INTERACTION = "MysticQuests_Conversation";
    private static final AtomicBoolean PAGE_SUPPLIER_REGISTERED = new AtomicBoolean();
    private static volatile ConversationService activePageService;

    private final Supplier<LoadedContent> contentSupplier;
    private final PlayerQuestService questService;
    private final QuestSignalBus signalBus;
    private final HytaleLogger logger;
    private final Map<UUID, ConversationSession> sessions = new ConcurrentHashMap<>();
    private final Map<UUID, Ref<EntityStore>> onlinePlayers = new ConcurrentHashMap<>();
    private final Map<String, Interactions> originalInteractions = new ConcurrentHashMap<>();
    private final Map<UUID, Long> lastReconcileNanos = new ConcurrentHashMap<>();

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

    public void registerPlayer(UUID playerId, Ref<EntityStore> playerEntity) {
        if (playerId == null || playerEntity == null || !playerEntity.isValid()) {
            return;
        }
        onlinePlayers.put(playerId, playerEntity);
        reconcileInteractables(playerEntity);
    }

    public void unregisterPlayer(UUID playerId) {
        if (playerId != null) {
            onlinePlayers.remove(playerId);
        }
    }

    public static void registerInteractionPageSupplier(JavaPlugin plugin) {
        if (!PAGE_SUPPLIER_REGISTERED.compareAndSet(false, true)) {
            return;
        }
        OpenCustomUIInteraction.registerCustomPageSupplier(
                plugin,
                MysticQuestsConversationPageSupplier.class,
                "MysticQuestsConversation",
                new MysticQuestsConversationPageSupplier());
    }

    public static void activatePageSupplier(ConversationService conversationService) {
        activePageService = conversationService;
    }

    public static void deactivatePageSupplier(ConversationService conversationService) {
        if (activePageService == conversationService) {
            activePageService = null;
        }
    }

    public void reconcileInteractablesAll() {
        for (Ref<EntityStore> playerEntity : onlinePlayers.values()) {
            reconcileInteractables(playerEntity);
        }
    }

    public void reconcileInteractablesThrottled(UUID playerId, Ref<EntityStore> playerEntity) {
        if (playerId == null) {
            return;
        }
        long now = System.nanoTime();
        Long previous = lastReconcileNanos.get(playerId);
        if (previous != null && now - previous < 1_000_000_000L) {
            return;
        }
        lastReconcileNanos.put(playerId, now);
        reconcileInteractables(playerEntity);
    }

    public void reconcileInteractables(Ref<EntityStore> playerEntity) {
        if (playerEntity == null || !playerEntity.isValid()) {
            return;
        }
        try {
            Store<EntityStore> store = playerEntity.getStore();
            World world = store.getExternalData().getWorld();
            Runnable action = () -> {
                try {
                    reconcileInteractablesInStore(store);
                } catch (RuntimeException exception) {
                    logger.at(Level.WARNING).withCause(exception).log("Failed to mark MysticQuests conversation entities interactable.");
                }
            };
            if (store.isInThread()) {
                action.run();
            } else {
                world.execute(action);
            }
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to schedule MysticQuests conversation entity reconciliation.");
        }
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
        if (sessions.containsKey(playerRef.getUuid())) {
            return true;
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

    public boolean tryStart(PlayerMouseButtonEvent event) {
        if (event.getMouseButton() == null || event.getMouseButton().state != MouseButtonState.Pressed) {
            return false;
        }
        Ref<EntityStore> targetRef = event.getTargetEntityRef();
        if (targetRef == null || !targetRef.isValid()) {
            return false;
        }
        Store<EntityStore> store = targetRef.getStore();
        Entity target = entityComponent(store, targetRef);
        if (target == null || target instanceof Player) {
            return false;
        }
        PlayerRef playerRef = event.getPlayerRefComponent();
        if (playerRef == null) {
            return false;
        }
        if (sessions.containsKey(playerRef.getUuid())) {
            return true;
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

    public boolean tryStartHyCitizen(PlayerRef playerRef, CitizenView citizen) {
        if (playerRef == null || citizen == null) {
            return false;
        }
        if (sessions.containsKey(playerRef.getUuid())) {
            return true;
        }
        ConversationDefinition conversation = findHyCitizenConversation(citizen);
        if (conversation == null) {
            return false;
        }
        ConversationNode node = node(conversation, conversation.start());
        QuestTargetContext targetContext = hyCitizenContext(citizen);
        if (node == null || !conditionsPass(playerRef.getUuid(), conversation, node.conditions(), targetContext)) {
            return false;
        }
        Ref<EntityStore> playerEntity = playerRef.getReference();
        if (playerEntity == null || !playerEntity.isValid()) {
            return false;
        }
        Store<EntityStore> store = playerEntity.getStore();
        Runnable action = () -> {
            Player player = store.getComponent(playerEntity, Player.getComponentType());
            if (player == null) {
                return;
            }
            sessions.put(playerRef.getUuid(), new ConversationSession(
                    playerRef.getUuid(),
                    conversation.packageId() + ":" + conversation.id(),
                    node.id(),
                    targetContext));
            enterNode(playerRef.getUuid(), conversation, node, targetContext);
            openPage(playerEntity, store, player, playerRef);
        };
        if (store.isInThread()) {
            action.run();
        } else {
            store.getExternalData().getWorld().execute(action);
        }
        return true;
    }

    public CustomUIPage tryCreateInteractionPage(
            Ref<EntityStore> playerEntity,
            ComponentAccessor<EntityStore> accessor,
            PlayerRef playerRef,
            InteractionContext interactionContext) {
        if (playerEntity == null || accessor == null || playerRef == null || interactionContext == null) {
            return null;
        }
        Ref<EntityStore> targetRef = interactionContext.getTargetEntity();
        if (targetRef == null || !targetRef.isValid()) {
            return null;
        }
        Entity target = entityComponent(accessor, targetRef);
        if (target == null || target instanceof Player) {
            return null;
        }
        ConversationDefinition conversation = findConversation(target);
        // Appending a document this build does not ship disconnects the player, so probe builds that
        // strip UI documents simply have no conversations.
        if (conversation == null || !UiDocuments.isShipped(UiDocuments.CONVERSATION)) {
            return null;
        }
        ConversationNode node = node(conversation, conversation.start());
        QuestTargetContext targetContext = entityContext(target);
        if (node == null || !conditionsPass(playerRef.getUuid(), conversation, node.conditions(), targetContext)) {
            return null;
        }
        sessions.put(playerRef.getUuid(), new ConversationSession(playerRef.getUuid(), conversation.packageId() + ":" + conversation.id(), node.id(), targetContext));
        enterNode(playerRef.getUuid(), conversation, node, targetContext);
        return new ConversationPage(playerRef, playerRef.getUuid(), this);
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
        List<ConversationChoice> resolvedChoices = choices.stream().map(choice -> {
            ConversationChoice copy = new ConversationChoice();
            copy.setText(questService.resolveText(playerId, conversation.packageId(), choice.text()));
            copy.setNext(choice.next());
            copy.setConditions(choice.conditions());
            copy.setEvents(choice.events());
            return copy;
        }).toList();
        return new ConversationView(
                questService.resolveText(playerId, conversation.packageId(), conversation.speaker()),
                conversation.id(),
                questService.resolveText(playerId, conversation.packageId(), node.text()),
                resolvedChoices);
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

    /** Backs the {@code inConversation} quest condition. */
    public boolean isInConversation(UUID playerId) {
        return sessions.containsKey(playerId);
    }

    /**
     * Backs the {@code cancelConversation} quest event. Drops the session and closes the page when
     * the player entity is still reachable; the session is cleared either way.
     */
    public void cancel(UUID playerId) {
        if (sessions.remove(playerId) == null) {
            return;
        }
        Ref<EntityStore> playerEntity = onlinePlayers.get(playerId);
        if (playerEntity == null || !playerEntity.isValid()) {
            return;
        }
        try {
            Store<EntityStore> store = playerEntity.getStore();
            Runnable close = () -> {
                Player player = store.getComponent(playerEntity, Player.getComponentType());
                if (player != null) {
                    player.getPageManager().setPage(playerEntity, store, Page.None);
                }
            };
            if (store.isInThread()) {
                close.run();
            } else {
                store.getExternalData().getWorld().execute(close);
            }
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to cancel conversation for " + playerId + ".");
        }
    }

    private ConversationDefinition findConversation(Entity target) {
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> matches(conversation.entity(), target))
                .findFirst()
                .orElse(null);
    }

    private ConversationDefinition findHyCitizenConversation(CitizenView citizen) {
        ConversationDefinition byId = findHyCitizenConversation(citizen, MatchMode.HYCITIZENS_ID);
        if (byId != null) {
            return byId;
        }
        ConversationDefinition byUuid = findHyCitizenConversation(citizen, MatchMode.UUID);
        if (byUuid != null) {
            return byUuid;
        }
        ConversationDefinition byName = findHyCitizenConversation(citizen, MatchMode.NAME);
        if (byName != null) {
            return byName;
        }
        ConversationDefinition byGroup = findHyCitizenConversation(citizen, MatchMode.HYCITIZENS_GROUP);
        if (byGroup != null) {
            return byGroup;
        }
        return null;
    }

    private ConversationDefinition findHyCitizenConversation(CitizenView citizen, MatchMode mode) {
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> matchesHyCitizen(conversation.entity(), citizen, mode))
                .findFirst()
                .orElse(null);
    }

    private void reconcileInteractablesInStore(Store<EntityStore> store) {
        List<ConversationDefinition> conversations = contentSupplier.get().conversations().values().stream()
                .filter(conversation -> conversation.entity() != null)
                .toList();
        if (conversations.isEmpty()) {
            return;
        }
        List<InteractionMutation> mutations = new ArrayList<>();
        int[] changed = new int[1];
        store.forEachChunk((BiConsumer<ArchetypeChunk<EntityStore>, CommandBuffer<EntityStore>>) (chunk, buffer) -> {
            Archetype<EntityStore> archetype = chunk.getArchetype();
            if (archetype.contains(Player.getComponentType())) {
                return;
            }
            for (int index = 0; index < chunk.size(); index++) {
                Ref<EntityStore> ref = chunk.getReferenceTo(index);
                if (ref == null || !ref.isValid()) {
                    continue;
                }
                Entity entity = entityComponent(chunk, index);
                if (entity == null || entity instanceof Player) {
                    continue;
                }
                ConversationEntityBinding matchedBinding = null;
                for (ConversationDefinition conversation : conversations) {
                    ConversationEntityBinding binding = conversation.entity();
                    if (matches(binding, entity)) {
                        matchedBinding = binding;
                        break;
                    }
                }
                if (matchedBinding != null) {
                    if (matchedBinding.showPrompt()) {
                        boolean hadInteractable = chunk.getComponent(index, Interactable.getComponentType()) != null;
                        String key = entityKey(entity);
                        if (key != null) {
                            mutations.add(new InteractionMutation(ref, key, matchedBinding, hadInteractable, false));
                        }
                    }
                    continue;
                }
                String key = entityKey(entity);
                if (key != null) {
                    mutations.add(new InteractionMutation(ref, key, null, true, true));
                }
            }
        });
        for (InteractionMutation mutation : mutations) {
            if (mutation.ref() == null || !mutation.ref().isValid()) {
                continue;
            }
            if (mutation.restore()) {
                restoreInteractions(store, mutation.ref(), mutation.entityKey());
                continue;
            }
            if (!mutation.hadInteractable()) {
                store.ensureComponent(mutation.ref(), Interactable.getComponentType());
                changed[0]++;
            }
            overrideInteractions(store, mutation.ref(), mutation.entityKey(), mutation.binding());
        }
        if (changed[0] > 0) {
            logger.at(Level.FINE).log("Marked " + changed[0] + " MysticQuests conversation entities as interactable.");
        }
    }

    private void overrideInteractions(Store<EntityStore> store, Ref<EntityStore> ref, String entityKey, ConversationEntityBinding binding) {
        if (entityKey == null) {
            return;
        }
        Interactions current = store.getComponent(ref, Interactions.getComponentType());
        if (current != null) {
            originalInteractions.putIfAbsent(entityKey, (Interactions) current.clone());
        }
        Interactions replacement = new Interactions();
        replacement.setInteractionHint(interactionHint(binding));
        replacement.setInteractionId(InteractionType.Use, CONVERSATION_ROOT_INTERACTION);
        if (current == null) {
            store.addComponent(ref, Interactions.getComponentType(), replacement);
        } else {
            store.replaceComponent(ref, Interactions.getComponentType(), replacement);
        }
    }

    private void restoreInteractions(Store<EntityStore> store, Ref<EntityStore> ref, String entityKey) {
        if (entityKey == null) {
            return;
        }
        Interactions original = originalInteractions.remove(entityKey);
        if (original != null) {
            store.replaceComponent(ref, Interactions.getComponentType(), original);
        }
    }

    private void queueInteractionHint(ArchetypeChunk<EntityStore> chunk, int index, Ref<EntityStore> ref, String hint) {
        EntityTrackerSystems.Visible visible = chunk.getComponent(index, EntityTrackerSystems.Visible.getComponentType());
        if (visible == null) {
            return;
        }
        InteractableUpdate update = new InteractableUpdate(hint);
        visible.visibleTo.values().forEach(viewer -> viewer.queueUpdate(ref, update));
        visible.newlyVisibleTo.values().forEach(viewer -> viewer.queueUpdate(ref, update));
    }

    private String interactionHint(ConversationEntityBinding binding) {
        if (binding == null || binding.interactionHint() == null || binding.interactionHint().isBlank()) {
            return "Press F to talk";
        }
        String hint = binding.interactionHint().trim();
        if (hint.equalsIgnoreCase("interactionHints.talk")) {
            return "Press F to talk";
        }
        if (hint.regionMatches(true, 0, "Press ", 0, "Press ".length())) {
            return hint;
        }
        if (hint.regionMatches(true, 0, "interactionHints.", 0, "interactionHints.".length())) {
            hint = hint.substring("interactionHints.".length());
        }
        return "Press F to " + hint;
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

    private boolean matchesHyCitizen(ConversationEntityBinding binding, CitizenView citizen, MatchMode mode) {
        if (binding == null || citizen == null) {
            return false;
        }
        return switch (mode) {
            case HYCITIZENS_ID -> equalsIgnoreCase(binding.hyCitizensId(), citizen.id());
            case HYCITIZENS_GROUP -> equalsIgnoreCase(binding.hyCitizensGroup(), citizen.group());
            case UUID -> equalsIgnoreCase(binding.uuid(), citizen.spawnedUuid());
            case NAME -> equalsIgnoreCase(binding.name(), citizen.name());
        };
    }

    public List<String> conversationsFor(CitizenView citizen) {
        if (citizen == null) {
            return List.of();
        }
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> conversation.entity() != null)
                .filter(conversation -> matchesHyCitizen(conversation.entity(), citizen, MatchMode.HYCITIZENS_ID)
                        || matchesHyCitizen(conversation.entity(), citizen, MatchMode.UUID)
                        || matchesHyCitizen(conversation.entity(), citizen, MatchMode.NAME)
                        || matchesHyCitizen(conversation.entity(), citizen, MatchMode.HYCITIZENS_GROUP))
                .map(conversation -> conversation.packageId() + ":" + conversation.id())
                .toList();
    }

    private boolean equalsIgnoreCase(String first, String second) {
        return first != null && second != null && !first.isBlank() && first.equalsIgnoreCase(second);
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

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity entityComponent(Store<EntityStore> store, Ref<EntityStore> targetRef) {
        return entityComponent((ComponentAccessor<EntityStore>) store, targetRef);
    }

    @SuppressWarnings({"rawtypes", "unchecked"})
    private Entity entityComponent(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> targetRef) {
        Archetype<EntityStore> archetype = accessor.getArchetype(targetRef);
        for (int index = 0; index < archetype.length(); index++) {
            ComponentType componentType = archetype.get(index);
            if (componentType == null || componentType.getTypeClass() == null) {
                continue;
            }
            if (Entity.class.isAssignableFrom(componentType.getTypeClass())) {
                Component component = accessor.getComponent(targetRef, componentType);
                if (component instanceof Entity entity) {
                    return entity;
                }
            }
        }
        return null;
    }

    private String entityKey(Entity entity) {
        return entity == null || entity.getUuid() == null ? null : entity.getUuid().toString();
    }

    private void openPage(Ref<EntityStore> playerEntity, Store<EntityStore> store, Player player, PlayerRef playerRef) {
        if (!UiDocuments.isShipped(UiDocuments.CONVERSATION)) {
            return;
        }
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

    private QuestTargetContext hyCitizenContext(CitizenView citizen) {
        String entityId = citizen.spawnedUuid() == null || citizen.spawnedUuid().isBlank()
                ? citizen.id()
                : citizen.spawnedUuid();
        String type = citizen.group() == null || citizen.group().isBlank()
                ? "HyCitizens"
                : "HyCitizens:" + citizen.group();
        return new QuestTargetContext(
                entityId,
                type,
                citizen.name(),
                null,
                null,
                citizen.worldUuid() == null ? "" : citizen.worldUuid(),
                null,
                null,
                null);
    }

    private record InteractionMutation(
            Ref<EntityStore> ref,
            String entityKey,
            ConversationEntityBinding binding,
            boolean hadInteractable,
            boolean restore) {
    }

    public static final class MysticQuestsConversationPageSupplier implements CustomPageSupplier {
        @Override
        public CustomUIPage tryCreate(
                Ref<EntityStore> playerEntity,
                ComponentAccessor<EntityStore> accessor,
                PlayerRef playerRef,
                InteractionContext context) {
            ConversationService conversationService = activePageService;
            return conversationService == null
                    ? null
                    : conversationService.tryCreateInteractionPage(playerEntity, accessor, playerRef, context);
        }
    }

    private enum MatchMode {
        HYCITIZENS_ID,
        HYCITIZENS_GROUP,
        UUID,
        NAME
    }

    private record ConversationSession(UUID playerId, String conversationId, String nodeId, QuestTargetContext targetContext) {
    }

    public record ConversationView(String speaker, String conversationId, String text, List<ConversationChoice> choices) {
    }
}
