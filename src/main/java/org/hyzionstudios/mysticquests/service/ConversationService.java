package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConversationChoice;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.ConversationEntityBinding;
import org.hyzionstudios.mysticquests.model.ConversationNode;
import org.hyzionstudios.mysticquests.integration.HyCitizensBridge.CitizenView;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge.GenerationNpc;
import org.hyzionstudios.mysticquests.ui.ConversationPage;
import org.hyzionstudios.mysticquests.ui.QuestHudCoordinator;
import org.hyzionstudios.mysticquests.ui.QuestHudService;
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
import java.util.concurrent.atomic.AtomicLong;
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
    private final Map<UUID, Map<String, ConversationChoice>> choiceTokens = new ConcurrentHashMap<>();
    private final Map<UUID, List<TranscriptLine>> transcripts = new ConcurrentHashMap<>();

    /**
     * Hands out the token that says which page currently owns a player's session.
     *
     * <p>Needed because {@code PageManager.openCustomPage} dismisses the outgoing page <em>after</em>
     * the replacement has been decided on: without a token, the old page's dismissal would clear the
     * session the new page is about to render.
     */
    private final AtomicLong sessionTokens = new AtomicLong();

    /** Null until MysticQuests has built the optional bridge, and when it is switched off. */
    private volatile MysticGenerationBridge generationBridge;
    private volatile VoicePlayer voicePlayer;
    private volatile QuestHudService hudService;

    /** Plays a node's narrative voice line to the reader; bound by the narrative integration. */
    @FunctionalInterface
    public interface VoicePlayer {
        void play(UUID playerId, String voice, String speakerEntity);
    }

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

    /**
     * Attaches the optional MysticGeneration bridge, enabling {@code generationDefinition} and
     * {@code generationUuid} bindings. Bound after construction because the bridge needs this
     * service's logger and lifecycle to already exist.
     */
    public void bindVoice(VoicePlayer voicePlayer) {
        this.voicePlayer = voicePlayer;
    }

    public void bindHud(QuestHudService hudService) {
        this.hudService = hudService;
    }

    public void bindGenerationSupport(MysticGenerationBridge generationBridge) {
        this.generationBridge = generationBridge;
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
            // A player who logs out mid-conversation must come back able to talk again, and neither
            // map is otherwise ever pruned.
            sessions.remove(playerId);
            choiceTokens.remove(playerId);
            transcripts.remove(playerId);
            clearCinematicHud(playerId);
            lastReconcileNanos.remove(playerId);
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
        GenerationNpc npc = identify(event.getTargetRef());
        ConversationDefinition conversation = findConversation(target, npc);
        if (conversation == null) {
            return false;
        }
        QuestTargetContext targetContext = entityContext(target, npc);
        ConversationNode node = openingNode(playerRef.getUuid(), conversation, targetContext);
        if (node == null) {
            return false;
        }
        long token = startSession(playerRef.getUuid(), conversation, node, targetContext);
        openPage(event.getPlayerRef(), event.getPlayerRef().getStore(), event.getPlayer(), playerRef, token);
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
        GenerationNpc npc = identify(targetRef);
        ConversationDefinition conversation = findConversation(target, npc);
        if (conversation == null) {
            return false;
        }
        QuestTargetContext targetContext = entityContext(target, npc);
        ConversationNode node = openingNode(playerRef.getUuid(), conversation, targetContext);
        if (node == null) {
            return false;
        }
        long token = startSession(playerRef.getUuid(), conversation, node, targetContext);
        openPage(event.getPlayerRef(), event.getPlayerRef().getStore(), event.getPlayer(), playerRef, token);
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
        QuestTargetContext targetContext = hyCitizenContext(citizen);
        ConversationNode node = openingNode(playerRef.getUuid(), conversation, targetContext);
        if (node == null) {
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
            long token = startSession(playerRef.getUuid(), conversation, node, targetContext);
            openPage(playerEntity, store, player, playerRef, token);
        };
        if (store.isInThread()) {
            action.run();
        } else {
            store.getExternalData().getWorld().execute(action);
        }
        return true;
    }

    /**
     * The page for an interaction the platform is about to open a custom UI for.
     *
     * <p>Called for the same key press that {@link #tryStart(PlayerInteractEvent)} and
     * {@link #tryStart(PlayerMouseButtonEvent)} already see. When one of those has started the
     * conversation, this must re-show the live session rather than start a second one — restarting
     * would rewind the dialogue to its opening node and run that node's events a second time, which
     * is what made conversations appear to repeat themselves.
     */
    public CustomUIPage tryCreateInteractionPage(
            Ref<EntityStore> playerEntity,
            ComponentAccessor<EntityStore> accessor,
            PlayerRef playerRef,
            InteractionContext interactionContext) {
        if (playerEntity == null || accessor == null || playerRef == null || interactionContext == null) {
            return null;
        }
        ConversationPage live = adoptSession(playerRef);
        if (live != null) {
            return live;
        }
        Ref<EntityStore> targetRef = interactionContext.getTargetEntity();
        if (targetRef == null || !targetRef.isValid()) {
            return null;
        }
        Entity target = entityComponent(accessor, targetRef);
        if (target == null || target instanceof Player) {
            return null;
        }
        GenerationNpc npc = identify(accessor, targetRef);
        ConversationDefinition conversation = findConversation(target, npc);
        // Appending a document this build does not ship disconnects the player, so probe builds that
        // strip UI documents simply have no conversations.
        if (conversation == null || !UiDocuments.isShipped(UiDocuments.CONVERSATION)) {
            return null;
        }
        QuestTargetContext targetContext = entityContext(target, npc);
        ConversationNode node = openingNode(playerRef.getUuid(), conversation, targetContext);
        if (node == null) {
            return null;
        }
        long token = startSession(playerRef.getUuid(), conversation, node, targetContext);
        return new ConversationPage(playerRef, playerRef.getUuid(), token, this);
    }

    public ConversationView view(UUID playerId) {
        ConversationSession session = sessions.get(playerId);
        if (session == null) {
            return null;
        }
        ConversationDefinition conversation = contentSupplier.get().conversations().get(session.conversationId());
        ConversationNode node = conversation == null ? null : node(conversation, session.nodeId());
        if (node == null) {
            // The conversation or its node went away in a reload: the session ends here, and so
            // must everything it put on screen.
            sessions.remove(playerId);
            choiceTokens.remove(playerId);
            transcripts.remove(playerId);
            clearCinematicHud(playerId);
            return null;
        }
        List<ConversationChoice> choices = visibleChoices(playerId, conversation, node, session.targetContext());
        Map<String, ConversationChoice> issuedTokens = new java.util.LinkedHashMap<>();
        List<ConversationOption> resolvedChoices = new ArrayList<>();
        for (int index = 0; index < choices.size(); index++) {
            String token = UUID.randomUUID().toString();
            issuedTokens.put(token, choices.get(index));
            resolvedChoices.add(new ConversationOption(
                    token,
                    questService.resolveText(playerId, conversation.packageId(), choices.get(index).text())));
        }
        choiceTokens.put(playerId, Map.copyOf(issuedTokens));
        return new ConversationView(
                questService.resolveText(playerId, conversation.packageId(), conversation.speaker()),
                conversation.id(),
                questService.resolveText(playerId, conversation.packageId(), node.text()),
                node.voice() != null,
                List.copyOf(transcripts.getOrDefault(playerId, List.of())),
                resolvedChoices);
    }

    public boolean choose(UUID playerId, String choiceToken, Ref<EntityStore> playerEntity, Store<EntityStore> store) {
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
        ConversationChoice choice = choiceTokens.getOrDefault(playerId, Map.of()).get(choiceToken);
        if (choice == null) {
            return true;
        }
        choiceTokens.remove(playerId);
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
        // Advancing keeps the token: the same page is still showing this conversation.
        sessions.put(playerId, session.atNode(nextNode.id()));
        enterNode(playerId, conversation, nextNode, session.targetContext());
        return true;
    }

    /**
     * Drops the session belonging to a page the client has closed.
     *
     * <p>Without this a dismissed page left its session behind: {@code inConversation} stayed true
     * for the rest of the login, and every later interaction with the NPC was swallowed by the
     * already-in-a-conversation guard. Ignores a stale token so that replacing a page — which
     * dismisses the outgoing one — cannot end the conversation the incoming page is showing.
     */
    public void pageDismissed(UUID playerId, long token) {
        if (playerId == null) {
            return;
        }
        sessions.computeIfPresent(playerId, (id, session) -> {
            if (session.token() != token) {
                return session;
            }
            choiceTokens.remove(playerId);
            transcripts.remove(playerId);
            clearCinematicHud(playerId);
            return null;
        });
    }

    public void end(UUID playerId, Ref<EntityStore> playerEntity, Store<EntityStore> store) {
        sessions.remove(playerId);
        choiceTokens.remove(playerId);
        transcripts.remove(playerId);
        clearCinematicHud(playerId);
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
        choiceTokens.remove(playerId);
        transcripts.remove(playerId);
        clearCinematicHud(playerId);
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

    /**
     * The conversation bound to an entity, most specific binding first.
     *
     * <p>A stable generation UUID names one NPC and so outranks a definition id, which names every
     * NPC of that kind; both outrank the entity's own UUID, type, and display name, because those
     * are the forms that break when a definition is republished.
     */
    private ConversationDefinition findConversation(Entity target, GenerationNpc npc) {
        if (npc != null) {
            ConversationDefinition byGeneration = findConversation(npc, MatchMode.GENERATION_UUID);
            if (byGeneration != null) {
                return byGeneration;
            }
            byGeneration = findConversation(npc, MatchMode.GENERATION_DEFINITION);
            if (byGeneration != null) {
                return byGeneration;
            }
        }
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> matches(conversation.entity(), target))
                .findFirst()
                .orElse(null);
    }

    private ConversationDefinition findConversation(GenerationNpc npc, MatchMode mode) {
        return contentSupplier.get().conversations().values().stream()
                .filter(conversation -> matchesGeneration(conversation.entity(), npc, mode))
                .findFirst()
                .orElse(null);
    }

    /** Chunk-local identity lookup, for the reconcile scan that already holds the chunk. */
    private GenerationNpc identify(ArchetypeChunk<EntityStore> chunk, int index) {
        MysticGenerationBridge bridge = generationBridge;
        return bridge == null ? null : bridge.identify(chunk, index).orElse(null);
    }

    /** The identity of an entity the player is interacting with, or null when it is not generated. */
    private GenerationNpc identify(Ref<EntityStore> ref) {
        return ref == null || !ref.isValid() ? null : identify(ref.getStore(), ref);
    }

    private GenerationNpc identify(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        MysticGenerationBridge bridge = generationBridge;
        return bridge == null ? null : bridge.identify(accessor, ref).orElse(null);
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
                GenerationNpc npc = identify(chunk, index);
                ConversationEntityBinding matchedBinding = null;
                for (ConversationDefinition conversation : conversations) {
                    ConversationEntityBinding binding = conversation.entity();
                    if (matches(binding, entity)
                            || matchesGeneration(binding, npc, MatchMode.GENERATION_UUID)
                            || matchesGeneration(binding, npc, MatchMode.GENERATION_DEFINITION)) {
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

    private boolean matchesGeneration(ConversationEntityBinding binding, GenerationNpc npc, MatchMode mode) {
        if (binding == null || npc == null) {
            return false;
        }
        return switch (mode) {
            case GENERATION_UUID -> equalsIgnoreCase(binding.generationUuid(), npc.uuid().toString());
            case GENERATION_DEFINITION -> equalsIgnoreCase(binding.generationDefinition(), npc.definitionId());
            default -> false;
        };
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
            // A HyCitizens citizen is never a MysticGeneration NPC; the two mods spawn their own.
            case GENERATION_UUID, GENERATION_DEFINITION -> false;
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

    private void openPage(Ref<EntityStore> playerEntity, Store<EntityStore> store, Player player, PlayerRef playerRef, long token) {
        if (!UiDocuments.isShipped(UiDocuments.CONVERSATION)) {
            return;
        }
        try {
            player.getPageManager().openCustomPage(playerEntity, store, new ConversationPage(playerRef, playerRef.getUuid(), token, this));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to open MysticQuests conversation page.");
        }
    }

    /**
     * Opens a session on {@code node}, runs that node's events, and returns the token identifying
     * the page that will show it.
     */
    private long startSession(
            UUID playerId,
            ConversationDefinition conversation,
            ConversationNode node,
            QuestTargetContext targetContext) {
        long token = sessionTokens.incrementAndGet();
        sessions.put(playerId, new ConversationSession(
                playerId,
                conversation.packageId() + ":" + conversation.id(),
                node.id(),
                targetContext,
                token));
        transcripts.put(playerId, new ArrayList<>());
        QuestHudService hud = hudService;
        if (hud != null) {
            hud.setContext(playerId, new QuestHudCoordinator.Context(true, false));
        }
        enterNode(playerId, conversation, node, targetContext);
        return token;
    }

    /**
     * A page showing the player's existing conversation from wherever it has reached, or null when
     * there is no session to show. Transfers ownership to the new page so that dismissing the one it
     * replaces does not end the conversation.
     */
    private ConversationPage adoptSession(PlayerRef playerRef) {
        ConversationSession session = sessions.get(playerRef.getUuid());
        if (session == null || !UiDocuments.isShipped(UiDocuments.CONVERSATION)) {
            return null;
        }
        long token = sessionTokens.incrementAndGet();
        sessions.put(playerRef.getUuid(), session.ownedBy(token));
        return new ConversationPage(playerRef, playerRef.getUuid(), token, this);
    }

    private void enterNode(UUID playerId, ConversationDefinition conversation, ConversationNode node, QuestTargetContext targetContext) {
        questService.executeEvents(playerId, conversation.packageId(), node.events(), targetContext);
        VoicePlayer voice = voicePlayer;
        if (voice != null && node.voice() != null) {
            voice.play(playerId, node.voice(), targetContext == null ? null : targetContext.entityId());
        }
        String conversationId = conversation.packageId() + ":" + conversation.id();
        transcripts.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(new TranscriptLine(
                questService.resolveText(playerId, conversation.packageId(), conversation.speaker()),
                questService.resolveText(playerId, conversation.packageId(), node.text())));
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

    /**
     * The node {@code conversation} should open on for this player, or null when none of its entry
     * points are available.
     *
     * <p>Candidates are tried in author order and the first whose conditions pass wins, so a
     * conversation can greet a player who has finished the quest differently from one who has not
     * started it. A conversation whose every candidate fails does not open at all — that is how a
     * single conditioned entry point has always behaved, and content relies on it to make an NPC
     * silent until something is true.
     */
    private ConversationNode openingNode(
            UUID playerId, ConversationDefinition conversation, QuestTargetContext targetContext) {
        for (String candidate : conversation.startCandidates()) {
            ConversationNode node = node(conversation, candidate);
            if (node != null && conditionsPass(playerId, conversation, node.conditions(), targetContext)) {
                return node;
            }
        }
        return null;
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

    private QuestTargetContext entityContext(Entity target, GenerationNpc npc) {
        QuestTargetContext context = new QuestTargetContext(
                target.getUuid() == null ? null : target.getUuid().toString(),
                target.getClass().getSimpleName(),
                target.getLegacyDisplayName(),
                null,
                null,
                "",
                null,
                null,
                null);
        return npc == null
                ? context
                : context.withGeneration(npc.definitionId(), npc.uuid().toString());
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
        GENERATION_UUID,
        GENERATION_DEFINITION,
        UUID,
        NAME
    }

    private record ConversationSession(
            UUID playerId, String conversationId, String nodeId, QuestTargetContext targetContext, long token) {

        ConversationSession atNode(String nextNodeId) {
            return new ConversationSession(playerId, conversationId, nextNodeId, targetContext, token);
        }

        ConversationSession ownedBy(long newToken) {
            return new ConversationSession(playerId, conversationId, nodeId, targetContext, newToken);
        }
    }

    private void clearCinematicHud(UUID playerId) {
        QuestHudService hud = hudService;
        if (hud != null) {
            hud.setContext(playerId, QuestHudCoordinator.Context.EXPLORATION);
        }
    }

    public record ConversationOption(String token, String text) {
    }

    public record TranscriptLine(String speaker, String text) {
    }

    public record ConversationView(
            String speaker,
            String conversationId,
            String text,
            boolean voiced,
            List<TranscriptLine> transcript,
            List<ConversationOption> choices) {
    }
}
