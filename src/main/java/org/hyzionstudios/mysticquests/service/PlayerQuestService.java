package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.VaultUnlockedEconomyBridge;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.api.QuestActionContext;
import org.hyzionstudios.mysticquests.api.QuestConditionHandler;
import org.hyzionstudios.mysticquests.api.QuestEventHandler;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;
import org.hyzionstudios.mysticquests.storage.QuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import org.joml.Vector3d;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.BiFunction;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.console.ConsoleSender;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class PlayerQuestService {
    /**
     * Every event type {@link #executeEvents} dispatches itself, before falling through to the
     * registry. Keep in step with that switch: the narrative v1 bridge validates against this list,
     * so a type missing here is refused in narrative content even though quests can run it.
     */
    public static final Set<String> BUILT_IN_EVENT_TYPES = Set.of(
            "tag", "variable", "addTag", "globalTag", "entityTag", "blockTag", "volumeTag", "removeTag",
            "setVariable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable",
            "removeVariable", "incrementVariable", "startQuest", "completeQuest", "modifyMoney",
            "triggerHyExtrasEffect", "notification", "sendMessage", "giveItem", "removeItem", "runCommand",
            "cancelConversation", "cancelQuest", "folder", "party", "if", "ref", "hidePlayer", "hideEntity",
            "showPlayer", "showEntity", "spawnNpc", "despawnNpc", "preventTargeting", "allowTargeting",
            "setCamera", "sendTitle", "actionBar");

    /** Every condition type {@link #evaluateCondition} handles itself; the same contract as above. */
    public static final Set<String> BUILT_IN_CONDITION_TYPES = Set.of(
            "tag", "globalTag", "entityTag", "blockTag", "volumeTag", "notTag", "questCompleted",
            "questActive", "variable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable",
            "economy", "and", "or", "not", "ref", "permission", "inConversation", "inParty", "partySize",
            "playerHidden", "entityHidden", "targetingPrevented", "nearEntity");

    private static final Pattern SCRIPT_PLACEHOLDER = Pattern.compile("(?<!\\\\)%([^%]+)%");
    /** Seconds a {@code sendTitle} stays on screen when the author does not say. */
    private static final BigDecimal DEFAULT_TITLE_DURATION = BigDecimal.valueOf(3);
    /** How far in front of the player a spawned NPC lands when content gives no coordinates. */
    private static final BigDecimal DEFAULT_SPAWN_DISTANCE = BigDecimal.valueOf(2);
    /** Seconds a {@code sendTitle} spends fading in and out when the author does not say. */
    private static final BigDecimal DEFAULT_TITLE_FADE = BigDecimal.valueOf(0.5);

    private final Supplier<LoadedContent> contentSupplier;
    private final QuestStorage storage;
    private final ScopedStateService scopedStateService;
    private final QuestPacketService packetService;
    private final VaultUnlockedEconomyBridge economyBridge;
    private final HyExtrasBridge hyExtrasBridge;
    private final QuestNotificationService notificationService;
    private final PlayerSessionService sessionService;
    private final PlayerInventoryService inventoryService;
    private final HytaleLogger logger;
    private final Map<UUID, PlayerQuestData> cache = new ConcurrentHashMap<>();
    private final Set<UUID> migratedPlayers = ConcurrentHashMap.newKeySet();
    private final List<Consumer<UUID>> changeListeners = new CopyOnWriteArrayList<>();

    /**
     * Set once the conversation service exists. Kept as indirection because ConversationService
     * depends on this service, so the two cannot be constructor-wired in both directions.
     */
    private volatile Supplier<Predicate<UUID>> conversationStateSupplier = () -> playerId -> false;
    private volatile Consumer<UUID> conversationCanceller = playerId -> {
    };
    private volatile Function<UUID, java.util.Collection<UUID>> partyMembers = playerId -> Set.of(playerId);
    private volatile BiFunction<UUID, String, String> externalTextResolver = (playerId, text) -> text;

    /**
     * Null until the runtime has built the services these actions need. Actions that require them
     * are skipped with a warning rather than throwing, so a partially-started server degrades
     * instead of failing a quest mid-flight.
     */
    private volatile QuestActionServices actionServices;

    public PlayerQuestService(
            Supplier<LoadedContent> contentSupplier,
            QuestStorage storage,
            ScopedStateService scopedStateService,
            QuestPacketService packetService,
            VaultUnlockedEconomyBridge economyBridge,
            HyExtrasBridge hyExtrasBridge,
            QuestNotificationService notificationService,
            PlayerSessionService sessionService,
            PlayerInventoryService inventoryService,
            HytaleLogger logger) {
        this.contentSupplier = contentSupplier;
        this.storage = storage;
        this.scopedStateService = scopedStateService;
        this.packetService = packetService;
        this.economyBridge = economyBridge;
        this.hyExtrasBridge = hyExtrasBridge;
        this.notificationService = notificationService;
        this.sessionService = sessionService;
        this.inventoryService = inventoryService;
        this.logger = logger;
    }

    /** Wires the conversation-dependent condition and event handlers once ConversationService exists. */
    public void bindConversationSupport(Predicate<UUID> inConversation, Consumer<UUID> cancelConversation) {
        this.conversationStateSupplier = () -> inConversation;
        this.conversationCanceller = cancelConversation;
    }

    public void bindPartySupport(Function<UUID, java.util.Collection<UUID>> partyMembers) {
        this.partyMembers = partyMembers;
    }

    /** Adds optional third-party placeholder expansion without making it a required dependency. */
    public void bindExternalTextResolver(BiFunction<UUID, String, String> resolver) {
        this.externalTextResolver = resolver == null ? (playerId, text) -> text : resolver;
    }

    /** Attaches visibility, targeting, target selection, and the extension registry. */
    public void bindActionServices(QuestActionServices services) {
        this.actionServices = services;
    }

    public void addChangeListener(Consumer<UUID> listener) {
        changeListeners.add(listener);
    }

    public QuestResult startQuest(UUID playerId, String questId) {
        LoadedContent content = contentSupplier.get();
        QuestDefinition quest = content.quests().get(questId);
        if (quest == null) {
            return QuestResult.failure("Unknown quest: " + questId);
        }
        PlayerQuestData data = data(playerId);
        if (data.activeQuests().containsKey(questId)) {
            return QuestResult.failure("Quest is already active: " + questId);
        }
        if (data.completedQuests().containsKey(questId)) {
            return QuestResult.failure("Quest is already completed: " + questId);
        }
        ReacceptState reaccept = reacceptState(playerId, questId);
        if (!reaccept.allowed()) {
            return QuestResult.failure(reaccept.reason());
        }
        for (ConditionDefinition condition : quest.startConditions()) {
            if (!evaluateCondition(data, quest.packageId(), quest, condition, QuestTargetContext.none())) {
                return QuestResult.failure("Start condition failed for " + quest.displayName());
            }
        }
        ActiveQuestData activeQuest = new ActiveQuestData(questId);
        for (ObjectiveDefinition objective : quest.objectives()) {
            activeQuest.objectiveProgress().put(objective.id(), 0);
        }
        data.activeQuests().put(questId, activeQuest);
        if (data.trackedQuestId() == null) {
            data.setTrackedQuestId(questId);
        }
        executeEvents(data, quest.packageId(), quest, quest.startEvents(), QuestTargetContext.none());
        save(data);
        return QuestResult.success("Started quest: " + quest.displayName());
    }

    public void startJoinQuests(UUID playerId) {
        LoadedContent content = contentSupplier.get();
        PlayerQuestData data = data(playerId);
        for (Map.Entry<String, QuestDefinition> entry : content.quests().entrySet()) {
            QuestDefinition quest = entry.getValue();
            String questId = entry.getKey();
            if (!quest.startOnJoin()
                    || data.activeQuests().containsKey(questId)
                    || data.completedQuests().containsKey(questId)) {
                continue;
            }
            QuestResult result = startQuest(playerId, questId);
            if (!result.success()) {
                logger.at(Level.FINE).log("Skipped join-start quest " + questId + " for " + playerId + ": " + result.message());
            }
        }
    }

    public QuestResult completeQuest(UUID playerId, String questId) {
        LoadedContent content = contentSupplier.get();
        QuestDefinition quest = content.quests().get(questId);
        if (quest == null) {
            return QuestResult.failure("Unknown quest: " + questId);
        }
        PlayerQuestData data = data(playerId);
        if (!data.activeQuests().containsKey(questId)) {
            return QuestResult.failure("Quest is not active: " + questId);
        }
        return completeQuest(data, quest);
    }

    public void handleSignal(QuestSignal signal) {
        handleSignal(signal, false);
    }

    /** Applies a copied party signal only to objectives explicitly marked shared/party. */
    public void handleSharedSignal(QuestSignal signal) {
        handleSignal(signal, true);
    }

    private void handleSignal(QuestSignal signal, boolean sharedOnly) {
        PlayerQuestData data = data(signal.playerId());
        LoadedContent content = contentSupplier.get();
        boolean changed = false;
        for (ActiveQuestData activeQuest : new ArrayList<>(data.activeQuests().values())) {
            QuestDefinition quest = content.quests().get(activeQuest.questId());
            if (quest == null) {
                continue;
            }
            for (ObjectiveDefinition objective : quest.objectives()) {
                if (sharedOnly && !objective.bool("shared", objective.bool("party", false))) {
                    continue;
                }
                if (matchesObjective(objective, signal)) {
                    int current = activeQuest.objectiveProgress().getOrDefault(objective.id(), 0);
                    int target = objective.integer("amount", 1);
                    activeQuest.objectiveProgress().put(objective.id(), Math.min(target, current + Math.max(1, signal.amount())));
                    changed = true;
                }
            }
            if (isComplete(quest, activeQuest)) {
                completeQuest(data, quest, signal.targetContext());
                changed = false;
            }
        }
        if (changed) {
            save(data);
        }
    }

    public List<JournalEntry> journal(UUID playerId) {
        PlayerQuestData data = data(playerId);
        LoadedContent content = contentSupplier.get();
        return data.activeQuests().values().stream()
                .map(active -> journalEntry(content.quests().get(active.questId()), active))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(JournalEntry::questId))
                .toList();
    }

    public List<JournalEntry> completedJournal(UUID playerId) {
        PlayerQuestData data = data(playerId);
        LoadedContent content = contentSupplier.get();
        return data.completedQuests().keySet().stream()
                .map(questId -> completedEntry(questId, content.quests().get(questId)))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(JournalEntry::questId))
                .toList();
    }

    public List<JournalEntry> availableJournal(UUID playerId) {
        PlayerQuestData data = data(playerId);
        LoadedContent content = contentSupplier.get();
        return content.quests().entrySet().stream()
                .filter(entry -> !data.activeQuests().containsKey(entry.getKey()))
                .filter(entry -> !data.completedQuests().containsKey(entry.getKey()))
                .filter(entry -> reacceptState(playerId, entry.getKey()).allowed())
                .filter(entry -> canStart(data, entry.getValue()))
                .map(entry -> availableEntry(entry.getKey(), entry.getValue()))
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(JournalEntry::questId))
                .toList();
    }

    /** Abandoned quests, newest first, each annotated with whether it can be taken again. */
    public List<JournalEntry> abandonedJournal(UUID playerId) {
        PlayerQuestData data = data(playerId);
        LoadedContent content = contentSupplier.get();
        return data.abandonedQuests().entrySet().stream()
                .filter(entry -> !data.activeQuests().containsKey(entry.getKey()))
                .filter(entry -> !data.completedQuests().containsKey(entry.getKey()))
                .sorted(Map.Entry.<String, Instant>comparingByValue().reversed())
                .map(entry -> abandonedEntry(playerId, entry.getKey(), content.quests().get(entry.getKey())))
                .filter(Objects::nonNull)
                .toList();
    }

    public JournalEntry trackedJournalEntry(UUID playerId) {
        PlayerQuestData data = data(playerId);
        normalizeTrackedQuest(data);
        String questId = data.trackedQuestId();
        if (questId == null) {
            return null;
        }
        LoadedContent content = contentSupplier.get();
        return journalEntry(content.quests().get(questId), data.activeQuests().get(questId));
    }

    public QuestResult trackQuest(UUID playerId, String questId) {
        PlayerQuestData data = data(playerId);
        if (!data.activeQuests().containsKey(questId)) {
            return QuestResult.failure("Quest is not active: " + questId);
        }
        data.setTrackedQuestId(questId);
        save(data);
        return QuestResult.success("Tracking quest: " + questId);
    }

    /**
     * Drops an active quest and clears its progress. Objective progress is discarded, so the quest
     * restarts from zero if it becomes available again; completion history is untouched.
     */
    public QuestResult abandonQuest(UUID playerId, String questId) {
        PlayerQuestData data = data(playerId);
        if (!data.activeQuests().containsKey(questId)) {
            return QuestResult.failure("Quest is not active: " + questId);
        }
        LoadedContent content = contentSupplier.get();
        QuestDefinition quest = content.quests().get(questId);
        data.activeQuests().remove(questId);
        data.questVariables().remove(questId);
        data.abandonedQuests().put(questId, Instant.now());
        if (questId.equals(data.trackedQuestId())) {
            data.setTrackedQuestId(null);
            normalizeTrackedQuest(data);
        }
        save(data);
        return QuestResult.success("Abandoned quest: " + (quest == null ? questId : quest.displayName()));
    }

    /**
     * Staff override that clears an abandonment record, making the quest immediately acceptable
     * again regardless of cooldown or re-accept conditions. Does not restore the objective progress
     * that abandoning discarded — the quest starts over from zero.
     */
    public QuestResult clearAbandoned(UUID playerId, String questId) {
        PlayerQuestData data = data(playerId);
        Instant abandonedAt = data.abandonedQuests().remove(questId);
        if (abandonedAt == null) {
            return QuestResult.failure("Quest is not recorded as abandoned: " + questId);
        }
        save(data);
        QuestDefinition quest = contentSupplier.get().quests().get(questId);
        return QuestResult.success(
                "Cleared abandonment of " + (quest == null ? questId : quest.displayName())
                        + " (abandoned " + abandonedAt + "). Progress is not restored.");
    }

    /** Sets one active objective to an exact value for staff testing and support. */
    public QuestResult setObjectiveProgress(UUID playerId, String questId, String objectiveId, int value) {
        LoadedContent content = contentSupplier.get();
        QuestDefinition quest = content.quests().get(questId);
        if (quest == null) {
            return QuestResult.failure("Unknown quest: " + questId);
        }
        PlayerQuestData data = data(playerId);
        ActiveQuestData active = data.activeQuests().get(questId);
        if (active == null) {
            return QuestResult.failure("Quest is not active: " + questId);
        }
        ObjectiveDefinition objective = quest.objectives().stream()
                .filter(candidate -> candidate.id().equals(objectiveId))
                .findFirst()
                .orElse(null);
        if (objective == null) {
            return QuestResult.failure("Unknown objective: " + objectiveId);
        }
        int target = Math.max(1, objective.integer("amount", 1));
        int updated = Math.max(0, Math.min(target, value));
        active.objectiveProgress().put(objectiveId, updated);
        if (isComplete(quest, active)) {
            return completeQuest(data, quest);
        }
        save(data);
        return QuestResult.success("Set " + objectiveId + " to " + updated + "/" + target + ".");
    }

    /** Clears every stored status and quest-scoped variable for one quest. */
    public QuestResult resetQuestState(UUID playerId, String questId) {
        PlayerQuestData data = data(playerId);
        boolean changed = data.activeQuests().remove(questId) != null;
        changed |= data.completedQuests().remove(questId) != null;
        changed |= data.abandonedQuests().remove(questId) != null;
        changed |= data.questVariables().remove(questId) != null;
        if (questId.equals(data.trackedQuestId())) {
            data.setTrackedQuestId(null);
            normalizeTrackedQuest(data);
            changed = true;
        }
        if (!changed) {
            return QuestResult.failure("No stored state for quest: " + questId);
        }
        save(data);
        return QuestResult.success("Reset quest state: " + questId);
    }

    /** Runs a named package canceler, cleaning only the state declared by that canceler. */
    public QuestResult cancelQuest(UUID playerId, String packageId, String cancelerId) {
        LoadedContent content = contentSupplier.get();
        String key = content.resolveId(packageId, cancelerId);
        com.fasterxml.jackson.databind.JsonNode canceler = content.cancelers().get(key);
        if (canceler == null || !canceler.isObject()) {
            return QuestResult.failure("Unknown quest canceler: " + key);
        }
        String cancelerPackage = packageOf(key);
        for (String conditionId : stringList(canceler.get("conditions"))) {
            ConditionDefinition reference = new ConditionDefinition();
            reference.setType("ref");
            reference.put("id", com.fasterxml.jackson.databind.node.TextNode.valueOf(conditionId));
            if (!evaluateCondition(playerId, cancelerPackage, reference)) {
                return QuestResult.failure("Cancel conditions are not met for " + key + ".");
            }
        }
        List<String> questIds = stringList(canceler.has("quests") ? canceler.get("quests") : canceler.get("quest"));
        if (questIds.isEmpty()) {
            questIds = data(playerId).activeQuests().keySet().stream()
                    .filter(id -> id.startsWith(cancelerPackage + ":"))
                    .toList();
        }
        for (String questId : questIds) {
            resetQuestState(playerId, content.resolveId(cancelerPackage, questId));
        }
        for (String tag : stringList(canceler.get("tags"))) {
            String scopedTag = tag.contains(".") ? tag : cancelerPackage + "." + tag;
            scopedStateService.removeTag("player", playerId.toString(), scopedTag);
        }
        for (String variable : stringList(canceler.get("variables"))) {
            scopedStateService.removeVariable("player", playerId.toString(), variable);
        }
        List<EventDefinition> actions = stringList(
                canceler.has("actions") ? canceler.get("actions") : canceler.get("events")).stream()
                .map(id -> {
                    EventDefinition reference = new EventDefinition();
                    reference.setType("ref");
                    reference.put("id", com.fasterxml.jackson.databind.node.TextNode.valueOf(id));
                    return reference;
                }).toList();
        executeEvents(playerId, cancelerPackage, actions);
        return QuestResult.success("Cancelled " + canceler.path("name").asText(cancelerId) + ".");
    }

    private List<String> stringList(com.fasterxml.jackson.databind.JsonNode node) {
        if (node == null || node.isNull()) {
            return List.of();
        }
        if (node.isArray()) {
            List<String> values = new ArrayList<>();
            node.forEach(value -> values.add(value.asText()));
            return List.copyOf(values);
        }
        if (node.isTextual()) {
            return java.util.Arrays.stream(node.asText().split(","))
                    .map(String::trim).filter(value -> !value.isBlank()).toList();
        }
        return List.of();
    }

    /** Abandoned quest IDs and when they were abandoned, for administrative inspection. */
    public Map<String, Instant> abandonedRecords(UUID playerId) {
        return Map.copyOf(data(playerId).abandonedQuests());
    }

    /**
     * Whether an abandoned quest may be accepted again, and why not when it may not.
     *
     * <p>A quest the player has never abandoned is always available. Otherwise the author must have
     * opted in: a cooldown, re-accept conditions, or both. When both are configured both must be
     * satisfied — use an {@code or} composite condition for either/or semantics. With neither
     * configured the abandonment is permanent.
     */
    public ReacceptState reacceptState(UUID playerId, String questId) {
        PlayerQuestData data = data(playerId);
        Instant abandonedAt = data.abandonedQuests().get(questId);
        if (abandonedAt == null) {
            return ReacceptState.permit();
        }
        QuestDefinition quest = contentSupplier.get().quests().get(questId);
        if (quest == null) {
            return ReacceptState.deny("This quest is no longer available.");
        }
        if (quest.abandonIsPermanent()) {
            return ReacceptState.deny("You abandoned this quest and it cannot be taken again.");
        }
        Integer cooldownSeconds = quest.abandonCooldownSeconds();
        if (cooldownSeconds != null) {
            Instant readyAt = abandonedAt.plusSeconds(cooldownSeconds);
            Duration remaining = Duration.between(Instant.now(), readyAt);
            if (!remaining.isNegative() && !remaining.isZero()) {
                return ReacceptState.deny("Available again in " + formatDuration(remaining) + ".");
            }
        }
        for (ConditionDefinition condition : quest.reacceptConditions()) {
            if (!evaluateCondition(data, quest.packageId(), quest, condition, QuestTargetContext.none())) {
                return ReacceptState.deny("You do not yet meet the requirements to take this quest again.");
            }
        }
        return ReacceptState.permit();
    }

    private static String formatDuration(Duration duration) {
        long totalSeconds = Math.max(1, duration.toSeconds());
        long days = totalSeconds / 86400;
        long hours = (totalSeconds % 86400) / 3600;
        long minutes = (totalSeconds % 3600) / 60;
        long seconds = totalSeconds % 60;
        if (days > 0) {
            return days + "d " + hours + "h";
        }
        if (hours > 0) {
            return hours + "h " + minutes + "m";
        }
        return minutes > 0 ? minutes + "m " + seconds + "s" : seconds + "s";
    }

    /** Result of a re-accept eligibility check; {@code reason} is player-facing when blocked. */
    public record ReacceptState(boolean allowed, String reason) {
        static ReacceptState permit() {
            return new ReacceptState(true, "");
        }

        static ReacceptState deny(String reason) {
            return new ReacceptState(false, reason);
        }
    }

    public QuestResult untrackQuest(UUID playerId) {
        PlayerQuestData data = data(playerId);
        data.setTrackedQuestId(null);
        normalizeTrackedQuest(data);
        save(data);
        if (data.trackedQuestId() == null) {
            return QuestResult.success("Quest tracking hidden.");
        }
        return QuestResult.success("Tracking quest: " + data.trackedQuestId());
    }

    public boolean evaluateCondition(UUID playerId, String packageId, ConditionDefinition condition) {
        return evaluateCondition(data(playerId), packageId, null, condition, QuestTargetContext.none());
    }

    public boolean evaluateCondition(UUID playerId, String packageId, ConditionDefinition condition, QuestTargetContext targetContext) {
        return evaluateCondition(data(playerId), packageId, null, condition, targetContext);
    }

    public void executeEvents(UUID playerId, String packageId, List<EventDefinition> events) {
        PlayerQuestData data = data(playerId);
        executeEvents(data, packageId, null, events, QuestTargetContext.none());
        save(data);
    }

    public void executeEvents(UUID playerId, String packageId, List<EventDefinition> events, QuestTargetContext targetContext) {
        PlayerQuestData data = data(playerId);
        executeEvents(data, packageId, null, events, targetContext);
        save(data);
    }

    public String placeholder(UUID playerId, String key) {
        PlayerQuestData data = data(playerId);
        if (key.equals("active_count")) {
            return Integer.toString(data.activeQuests().size());
        }
        if (key.equals("current_quest")) {
            return data.activeQuests().keySet().stream().findFirst().orElse("");
        }
        if (key.startsWith("quest_status_")) {
            String questId = key.substring("quest_status_".length());
            if (data.activeQuests().containsKey(questId)) {
                return "active";
            }
            if (data.completedQuests().containsKey(questId)) {
                return "completed";
            }
            return "inactive";
        }
        if (key.startsWith("tag_")) {
            ConditionDefinition condition = new ConditionDefinition();
            condition.setType("tag");
            condition.put("tag", com.fasterxml.jackson.databind.node.TextNode.valueOf(key.substring("tag_".length())));
            return Boolean.toString(scopedStateService.hasTag(playerId, condition, QuestTargetContext.none()));
        }
        if (key.startsWith("var_player_")) {
            ConditionDefinition condition = new ConditionDefinition();
            condition.setType("variable");
            condition.put("key", com.fasterxml.jackson.databind.node.TextNode.valueOf(key.substring("var_player_".length())));
            return Objects.toString(scopedStateService.variable(playerId, condition, QuestTargetContext.none()), "");
        }
        if (key.startsWith("objective_progress_")) {
            String[] parts = key.substring("objective_progress_".length()).split("_", 2);
            if (parts.length == 2) {
                ActiveQuestData quest = data.activeQuests().get(parts[0]);
                return quest == null ? "0" : Integer.toString(quest.objectiveProgress().getOrDefault(parts[1], 0));
            }
        }
        return "";
    }

    /** Resolves BetonQuest-style percent placeholders in authored text. */
    public String resolveText(UUID playerId, String packageId, String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String resolved = text;
        for (int depth = 0; depth < 8; depth++) {
            Matcher matcher = SCRIPT_PLACEHOLDER.matcher(resolved);
            StringBuffer output = new StringBuffer();
            boolean found = false;
            while (matcher.find()) {
                found = true;
                matcher.appendReplacement(output, Matcher.quoteReplacement(
                        scriptPlaceholder(playerId, packageId, matcher.group(1))));
            }
            matcher.appendTail(output);
            resolved = output.toString();
            if (!found) {
                break;
            }
        }
        return resolved.replace("\\%", "%");
    }

    /**
     * Sends rich chat to the triggering player or every online player.
     *
     * <p>PlaceholderAPI runs first so third-party percent placeholders are not consumed by the
     * MysticQuests script-placeholder parser. MysticQuests placeholders and legacy/hex color codes
     * are then resolved independently for each recipient.
     */
    public void sendRichMessage(UUID triggeringPlayer, String packageId, String message, boolean global) {
        Iterable<UUID> recipients = global ? sessionService.onlinePlayerIds() : List.of(triggeringPlayer);
        for (UUID recipient : recipients) {
            String externallyResolved = externalTextResolver.apply(recipient, message == null ? "" : message);
            String resolved = resolveText(
                    recipient,
                    packageId == null ? "" : packageId,
                    externallyResolved == null ? "" : externallyResolved);
            notificationService.sendChat(recipient, resolved, "#EEF3FC");
        }
    }

    private String scriptPlaceholder(UUID playerId, String packageId, String rawExpression) {
        String expression = rawExpression;
        String effectivePackage = packageId;
        int crossPackage = expression.indexOf('>');
        if (crossPackage > 0) {
            effectivePackage = expression.substring(0, crossPackage).replace('-', '/');
            expression = expression.substring(crossPackage + 1);
        }
        String[] parts = expression.split("\\.");
        if (parts.length == 0) {
            return "";
        }
        PlayerQuestData player = data(playerId);
        return switch (parts[0].toLowerCase()) {
            case "player" -> playerPlaceholder(playerId, parts);
            case "constant" -> parts.length < 2 ? "" : contentSupplier.get().constants()
                    .getOrDefault(effectivePackage + ":" + parts[1], com.fasterxml.jackson.databind.node.TextNode.valueOf(""))
                    .asText();
            case "tag" -> parts.length < 2 ? "false" : Boolean.toString(
                    scopedStateService.tags("player", playerId.toString()).contains(parts[1]));
            case "globaltag" -> parts.length < 2 ? "false" : Boolean.toString(
                    scopedStateService.tags("global", ScopedStateService.GLOBAL_OWNER).contains(parts[1]));
            case "variable", "var" -> parts.length < 2 ? "" : scopedStateService.variables("player", playerId.toString())
                    .getOrDefault(parts[1], "");
            case "point" -> parts.length < 2 ? "0" : scopedStateService.variables("player", playerId.toString())
                    .getOrDefault("point." + parts[1], "0");
            case "globalpoint" -> parts.length < 2 ? "0" : scopedStateService.variables("global", ScopedStateService.GLOBAL_OWNER)
                    .getOrDefault("point." + parts[1], "0");
            case "condition" -> conditionPlaceholder(playerId, effectivePackage, parts);
            case "quest" -> questPlaceholder(player, effectivePackage, parts);
            case "objective" -> objectivePlaceholder(player, effectivePackage, parts);
            case "randomnumber" -> randomPlaceholder(parts);
            case "function" -> functionPlaceholder(playerId, effectivePackage, parts);
            default -> placeholder(playerId, rawExpression.replace('.', '_'));
        };
    }

    private String playerPlaceholder(UUID playerId, String[] parts) {
        if (parts.length > 1 && parts[1].equalsIgnoreCase("uuid")) {
            return playerId.toString();
        }
        PlayerRef playerRef = sessionService.playerRef(playerId);
        return playerRef == null ? playerId.toString() : Objects.toString(playerRef.getUsername(), playerId.toString());
    }

    private String conditionPlaceholder(UUID playerId, String packageId, String[] parts) {
        if (parts.length < 2) {
            return "false";
        }
        ConditionDefinition reference = new ConditionDefinition();
        reference.setType("ref");
        reference.put("id", com.fasterxml.jackson.databind.node.TextNode.valueOf(parts[1]));
        boolean result = evaluateCondition(playerId, packageId, reference);
        return parts.length > 2 && parts[2].equalsIgnoreCase("papiMode") ? (result ? "yes" : "no") : Boolean.toString(result);
    }

    private String questPlaceholder(PlayerQuestData player, String packageId, String[] parts) {
        if (parts.length < 2) {
            return "";
        }
        String id = resolve(packageId, parts[1]);
        if (player.activeQuests().containsKey(id)) {
            return "active";
        }
        if (player.completedQuests().containsKey(id)) {
            return "completed";
        }
        return player.abandonedQuests().containsKey(id) ? "abandoned" : "inactive";
    }

    private String objectivePlaceholder(PlayerQuestData player, String packageId, String[] parts) {
        if (parts.length < 2) {
            return "";
        }
        String objectiveId = parts[1];
        for (ActiveQuestData active : player.activeQuests().values()) {
            if (!active.questId().startsWith(packageId + ":") || !active.objectiveProgress().containsKey(objectiveId)) {
                continue;
            }
            QuestDefinition quest = contentSupplier.get().quests().get(active.questId());
            ObjectiveDefinition objective = quest == null ? null : quest.objectives().stream()
                    .filter(candidate -> objectiveId.equals(candidate.id())).findFirst().orElse(null);
            int current = active.objectiveProgress().getOrDefault(objectiveId, 0);
            int total = objective == null ? 1 : Math.max(1, objective.integer("amount", 1));
            String property = parts.length < 3 ? "amount" : parts[2].toLowerCase();
            return switch (property) {
                case "left" -> Integer.toString(Math.max(0, total - current));
                case "total" -> Integer.toString(total);
                default -> Integer.toString(current);
            };
        }
        return "";
    }

    private String randomPlaceholder(String[] parts) {
        if (parts.length < 3 || !parts[2].contains("~")) {
            return "0";
        }
        String[] range = parts[2].split("~", 2);
        try {
            long minimum = Long.parseLong(range[0]);
            long maximum = Long.parseLong(range[1]);
            if (minimum >= maximum) {
                return Long.toString(minimum);
            }
            return Long.toString(ThreadLocalRandom.current().nextLong(minimum, maximum + 1));
        } catch (NumberFormatException exception) {
            return "0";
        }
    }

    private String functionPlaceholder(UUID playerId, String packageId, String[] parts) {
        if (parts.length < 2) {
            return "";
        }
        com.fasterxml.jackson.databind.JsonNode definition = contentSupplier.get().functions().get(packageId + ":" + parts[1]);
        if (definition == null) {
            return "";
        }
        String expression = definition.isTextual()
                ? definition.asText()
                : definition.path("expression").asText(definition.path("value").asText(""));
        for (int index = 2; index < parts.length; index++) {
            expression = expression.replace("$" + (index - 1), parts[index])
                    .replace("{" + (index - 2) + "}", parts[index]);
        }
        return resolveText(playerId, packageId, expression);
    }

    public PlayerQuestData data(UUID playerId) {
        return cache.computeIfAbsent(playerId, id -> {
            try {
                PlayerQuestData data = storage.loadPlayer(id);
                if (migratedPlayers.add(id)) {
                    scopedStateService.migrateLegacyPlayer(data);
                }
                return data;
            } catch (IOException exception) {
                logger.at(Level.WARNING).log("Failed to load quest data for " + id + ": " + exception.getMessage());
                return new PlayerQuestData(id);
            }
        });
    }

    private QuestResult completeQuest(PlayerQuestData data, QuestDefinition quest) {
        return completeQuest(data, quest, QuestTargetContext.none());
    }

    private QuestResult completeQuest(PlayerQuestData data, QuestDefinition quest, QuestTargetContext targetContext) {
        String questId = quest.packageId() + ":" + quest.id();
        data.activeQuests().remove(questId);
        data.completedQuests().put(questId, Instant.now());
        if (questId.equals(data.trackedQuestId())) {
            data.setTrackedQuestId(null);
            normalizeTrackedQuest(data);
        }
        executeEvents(data, quest.packageId(), quest, quest.completeEvents(), targetContext);
        executeEvents(data, quest.packageId(), quest, quest.rewards(), targetContext);
        save(data);
        return QuestResult.success("Completed quest: " + quest.displayName());
    }

    private void executeEvents(PlayerQuestData data, String packageId, QuestDefinition quest, List<EventDefinition> events, QuestTargetContext targetContext) {
        executeEvents(data, packageId, quest, events, targetContext, new java.util.LinkedHashSet<>());
    }

    private void executeEvents(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            List<EventDefinition> events,
            QuestTargetContext targetContext,
            Set<String> referenceStack) {
        for (EventDefinition event : events) {
            switch (event.type()) {
                // Canonical state types; the loader rewrites every legacy alias onto these, so the
                // aliases below only fire for definitions built in code (commands, trigger volumes).
                case "tag" -> applyTag(data, packageId, event, targetContext, event.text("op", "add"));
                case "variable" -> applyVariable(data, packageId, quest, event, targetContext, event.text("op", "set"));
                case "addTag", "globalTag", "entityTag", "blockTag", "volumeTag" -> applyTag(data, packageId, event, targetContext, "add");
                case "removeTag" -> applyTag(data, packageId, event, targetContext, "remove");
                case "setVariable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> applyVariable(data, packageId, quest, event, targetContext, "set");
                case "removeVariable" -> applyVariable(data, packageId, quest, event, targetContext, "remove");
                case "incrementVariable" -> applyVariable(data, packageId, quest, event, targetContext, "increment");
                case "startQuest" -> startQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "completeQuest" -> completeQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "modifyMoney" -> economyBridge.modify(data.playerId(), event.text("account", data.playerId().toString()), event.decimal("amount", BigDecimal.ZERO));
                case "triggerHyExtrasEffect" -> hyExtrasBridge.trigger(event.text("action", ""), data.playerId());
                case "notification" -> sendNotification(data.playerId(), packageId, event);
                case "sendMessage" -> notificationService.sendChat(
                        data.playerId(),
                        resolveText(data.playerId(), packageId, event.text("message", event.text("text", ""))),
                        event.text("color", event.text("messageColor", "#EEF3FC")));
                case "giveItem" -> inventoryService.give(data.playerId(), resolveItemEvent(packageId, event));
                case "removeItem" -> inventoryService.remove(data.playerId(), resolveItemEvent(packageId, event));
                case "runCommand" -> runCommand(data.playerId(), packageId, event);
                case "cancelConversation" -> conversationCanceller.accept(data.playerId());
                case "cancelQuest" -> cancelQuest(data.playerId(), packageId, event.text("canceler", event.text("id", "")));
                case "folder" -> executeEvents(data, packageId, quest, event.children("events", EventDefinition::new), targetContext, referenceStack);
                case "party" -> executePartyEvents(data.playerId(), packageId, event.children("events", EventDefinition::new), targetContext);
                case "if" -> executeConditional(data, packageId, quest, event, targetContext, referenceStack);
                case "ref" -> executeEventReference(data, packageId, quest, event, targetContext, referenceStack);
                case "hidePlayer", "hideEntity" -> applyVisibility(data.playerId(), event, targetContext, true);
                case "showPlayer", "showEntity" -> applyVisibility(data.playerId(), event, targetContext, false);
                case "spawnNpc" -> spawnGeneratedNpc(data, event, targetContext);
                case "despawnNpc" -> despawnGeneratedNpc(data.playerId(), event, targetContext);
                case "preventTargeting" -> applyTargeting(data.playerId(), event, targetContext, true);
                case "allowTargeting" -> applyTargeting(data.playerId(), event, targetContext, false);
                case "setCamera" -> applyCamera(data.playerId(), event, targetContext);
                case "sendTitle" -> applyTitle(data.playerId(), packageId, event, targetContext);
                case "actionBar" -> applyActionBar(data.playerId(), packageId, event, targetContext);
                default -> executeRegisteredEvent(data.playerId(), packageId, event, targetContext);
            }
        }
    }

    private void executePartyEvents(UUID actorId, String packageId, List<EventDefinition> events, QuestTargetContext targetContext) {
        for (UUID member : partyMembers.apply(actorId)) {
            executeEvents(member, packageId, events, targetContext);
        }
    }

    private void executeEventReference(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            EventDefinition reference,
            QuestTargetContext targetContext,
            Set<String> stack) {
        LoadedContent content = contentSupplier.get();
        String key = content.resolveId(packageId, reference.text("id", reference.text("ref", "")));
        EventDefinition event = content.events().get(key);
        if (event == null) {
            logger.at(Level.WARNING).log("Unknown named event reference: " + key);
            return;
        }
        if (!stack.add(key)) {
            logger.at(Level.WARNING).log("Named event reference cycle: " + stack + " -> " + key);
            return;
        }
        executeEvents(data, packageOf(key), quest, List.of(event), targetContext, stack);
        stack.remove(key);
    }

    private void executeConditional(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            EventDefinition event,
            QuestTargetContext targetContext,
            Set<String> referenceStack) {
        List<ConditionDefinition> conditions = event.children("conditions", ConditionDefinition::new);
        if (conditions.isEmpty()) {
            conditions = event.children("condition", ConditionDefinition::new);
        }
        boolean passed = !conditions.isEmpty()
                && conditions.stream().allMatch(condition -> evaluateCondition(
                        data, packageId, quest, condition, targetContext, new java.util.LinkedHashSet<>()));
        List<EventDefinition> branch = passed
                ? event.children("then", EventDefinition::new)
                : event.children("else", EventDefinition::new);
        executeEvents(data, packageId, quest, branch, targetContext, referenceStack);
    }

    /**
     * Dispatches a console-style command as the player, so the server re-checks their permissions.
     * Content files therefore cannot escalate past what the player could type themselves.
     */
    private void runCommand(UUID playerId, String packageId, EventDefinition event) {
        String command = event.text("command", event.text("value", ""));
        String executeAs = event.text("executeAs", event.text("as", "player"));
        runCommand(playerId, packageId, command, executeAs.equalsIgnoreCase("console"));
    }

    /** Runs a placeholder-aware command as the player or as the permission-unrestricted console. */
    public void runCommand(UUID playerId, String packageId, String command, boolean console) {
        if (command == null || command.isBlank()) {
            return;
        }
        String externallyResolved = externalTextResolver.apply(playerId, command);
        String resolved = resolveText(
                playerId,
                packageId == null ? "" : packageId,
                externallyResolved == null ? "" : externallyResolved);
        String normalized = resolved.startsWith("/") ? resolved.substring(1) : resolved;
        PlayerRef playerRef = console ? null : sessionService.playerRef(playerId);
        if (!console && playerRef == null) {
            logger.at(Level.FINE).log("Skipped runCommand for offline player " + playerId + ".");
            return;
        }
        try {
            CommandManager.get().handleCommand(console ? ConsoleSender.INSTANCE : playerRef, normalized);
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception)
                    .log("Failed to run quest command as " + (console ? "console" : "player " + playerId) + ".");
        }
    }

    /**
     * Hides or shows one or more subjects from one or more viewers.
     *
     * <p>{@code target} selects what is hidden and defaults to whatever the trigger fired against;
     * {@code viewer} selects who stops seeing it and defaults to the acting player. Both accept the
     * full {@link TargetSelector} syntax, so "hide every guard from the whole party" is one event.
     */
    private void applyVisibility(UUID playerId, EventDefinition event, QuestTargetContext targetContext, boolean hide) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        List<UUID> viewers = services.targets().resolve(event.text("viewer", "self"), playerId, targetContext);
        List<UUID> subjects = services.targets().resolve(event.text("target", "context"), playerId, targetContext);
        for (UUID viewer : viewers) {
            for (UUID subject : subjects) {
                if (hide) {
                    services.visibility().hide(viewer, subject);
                } else {
                    services.visibility().show(viewer, subject);
                }
            }
        }
    }

    /**
     * Spawns a MysticGeneration NPC from an authored definition.
     *
     * <p>Placed in front of the acting player unless explicit coordinates are given, which is what a
     * quest-giver appearing mid-conversation wants. The spawn is MysticGeneration's own, so the NPC
     * arrives with a stable identity already attached; {@code variable} captures that identity into
     * a player variable so a later step can find this exact NPC again — without it, content spawning
     * two of the same definition has no way to tell them apart.
     */
    private void spawnGeneratedNpc(PlayerQuestData data, EventDefinition event, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        MysticGenerationBridge generation = services.generation();
        if (generation == null || !generation.available()) {
            logger.at(Level.FINE).log("Skipped spawnNpc: MysticGeneration is not available.");
            return;
        }
        String definition = event.text("definition", event.text("npc", ""));
        if (definition.isBlank()) {
            logger.at(Level.WARNING).log("A spawnNpc action named no definition.");
            return;
        }
        PlayerRef playerRef = sessionService.playerRef(data.playerId());
        if (playerRef == null) {
            return;
        }
        Ref<EntityStore> playerEntity = playerRef.getReference();
        if (playerEntity == null || !playerEntity.isValid()) {
            return;
        }
        Transform transform = playerRef.getTransform();
        if (transform == null) {
            return;
        }
        Vector3d position = spawnPosition(event, transform);
        float yaw = (float) event.decimal("yaw", BigDecimal.valueOf(transform.getRotation().yaw())).doubleValue();
        String variable = event.text("variable", "");
        generation.spawn(playerEntity.getStore(), definition, position, yaw, npc -> {
            if (!variable.isBlank()) {
                scopedStateService.setVariable(
                        ScopedStateService.normalizeScope("player"),
                        data.playerId().toString(),
                        variable,
                        npc.uuid().toString());
            }
        });
    }

    /** Explicit coordinates when content gives them, otherwise just in front of the player. */
    private Vector3d spawnPosition(EventDefinition event, Transform transform) {
        if (event.text("x").isPresent() && event.text("y").isPresent() && event.text("z").isPresent()) {
            return new Vector3d(
                    event.decimal("x", BigDecimal.ZERO).doubleValue(),
                    event.decimal("y", BigDecimal.ZERO).doubleValue(),
                    event.decimal("z", BigDecimal.ZERO).doubleValue());
        }
        double distance = event.decimal("distance", DEFAULT_SPAWN_DISTANCE).doubleValue();
        return new Vector3d(transform.getPosition())
                .add(new Vector3d(transform.getDirection()).mul(distance));
    }

    /**
     * Removes generated NPCs, by default the one the trigger fired against.
     *
     * <p>Targets resolve to MysticGeneration identities rather than entity UUIDs, so
     * {@code generation:<definition>} despawns every NPC of a kind and a captured variable despawns
     * exactly the one a quest spawned earlier.
     */
    private void despawnGeneratedNpc(UUID playerId, EventDefinition event, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        MysticGenerationBridge generation = services.generation();
        if (generation == null || !generation.available()) {
            logger.at(Level.FINE).log("Skipped despawnNpc: MysticGeneration is not available.");
            return;
        }
        PlayerRef playerRef = sessionService.playerRef(playerId);
        Ref<EntityStore> playerEntity = playerRef == null ? null : playerRef.getReference();
        if (playerEntity == null || !playerEntity.isValid()) {
            return;
        }
        for (UUID npc : services.targets().resolve(event.text("target", "generation"), playerId, targetContext)) {
            generation.despawn(playerEntity.getStore(), npc);
        }
    }

    /** Grants or removes protection from NPC targeting. Defaults to the acting player. */
    private void applyTargeting(UUID playerId, EventDefinition event, QuestTargetContext targetContext, boolean prevent) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        for (UUID subject : services.targets().resolve(event, playerId, targetContext)) {
            if (prevent) {
                services.targeting().protectPlayer(subject);
            } else {
                services.targeting().unprotectPlayer(subject);
            }
        }
    }

    private void applyCamera(UUID playerId, EventDefinition event, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        QuestPacketService.CameraMode mode = QuestPacketService.parseCameraMode(event.text("mode", "first"));
        boolean locked = event.bool("locked", false);
        for (UUID subject : services.targets().resolve(event, playerId, targetContext)) {
            packetService.setCamera(subject, mode, locked);
        }
    }

    private void applyTitle(UUID playerId, String packageId, EventDefinition event, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        String title = event.text("title", event.text("text", ""));
        String subtitle = event.text("subtitle", "");
        float duration = (float) event.decimal("duration", DEFAULT_TITLE_DURATION).doubleValue();
        float fadeIn = (float) event.decimal("fadeIn", DEFAULT_TITLE_FADE).doubleValue();
        float fadeOut = (float) event.decimal("fadeOut", DEFAULT_TITLE_FADE).doubleValue();
        String color = event.text("color", "");
        for (UUID subject : services.targets().resolve(event, playerId, targetContext)) {
            packetService.sendTitle(
                    subject,
                    resolveText(subject, packageId, title),
                    subtitle.isBlank() ? null : resolveText(subject, packageId, subtitle),
                    duration,
                    fadeIn,
                    fadeOut,
                    color);
        }
    }

    private void applyActionBar(UUID playerId, String packageId, EventDefinition event, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            logSkipped(event.type());
            return;
        }
        String message = event.text("message", event.text("text", ""));
        String color = event.text("color", "");
        for (UUID subject : services.targets().resolve(event, playerId, targetContext)) {
            packetService.sendActionBar(subject, resolveText(subject, packageId, message), color);
        }
    }

    /**
     * Last stop for an unrecognised event type: a type another mod registered through the public API.
     * A handler that throws is contained here so the remaining events in the list still run.
     */
    private void executeRegisteredEvent(
            UUID playerId,
            String packageId,
            EventDefinition event,
            QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        QuestEventHandler handler = services == null ? null : services.registry().event(event.type());
        if (handler == null) {
            logger.at(Level.WARNING).log("Unknown event type at execution: " + event.type());
            return;
        }
        try {
            handler.execute(actionContext(playerId, packageId, event, targetContext));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception)
                    .log("Registered MysticQuests event '" + event.type() + "' failed.");
        }
    }

    private QuestActionContext actionContext(
            UUID playerId,
            String packageId,
            TypedConfig definition,
            QuestTargetContext targetContext) {
        return new QuestActionContext(
                playerId,
                packageId,
                definition,
                targetContext == null ? QuestTargetContext.none() : targetContext,
                text -> resolveText(playerId, packageId, text));
    }

    private void logSkipped(String type) {
        logger.at(Level.WARNING).log(
                "Skipped MysticQuests event '" + type + "': runtime services are not attached yet.");
    }

    /** Applies a canonical {@code tag} event. {@code op} is {@code add} or {@code remove}. */
    private void applyTag(
            PlayerQuestData data,
            String packageId,
            EventDefinition event,
            QuestTargetContext targetContext,
            String op) {
        EventDefinition scoped = packageScopedTag(packageId, event);
        boolean remove = op.equalsIgnoreCase("remove");
        if (remove) {
            scopedStateService.removeTag(data.playerId(), scoped, targetContext);
        } else {
            scopedStateService.addTag(data.playerId(), scoped, targetContext);
        }
        // Player-scope tags are mirrored onto the player record, which older content and the
        // journal still read from.
        if (effectiveScope(scoped).equals("player")) {
            String tag = scoped.text("tag", "");
            if (remove) {
                data.tags().remove(tag);
            } else {
                data.tags().add(tag);
            }
        }
    }

    /** Applies a canonical {@code variable} event. {@code op} is {@code set}, {@code remove}, or {@code increment}. */
    private void applyVariable(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            EventDefinition event,
            QuestTargetContext targetContext,
            String op) {
        String key = event.text("key", event.text("name", ""));
        if (key.isBlank()) {
            return;
        }
        if (isQuestScope(event)) {
            applyQuestVariable(data, packageId, quest, event, key, op);
            return;
        }
        String scope = effectiveScope(event);
        switch (op.toLowerCase(Locale.ROOT)) {
            case "remove" -> {
                scopedStateService.removeVariable(data.playerId(), event, targetContext);
                if (scope.equals("player")) {
                    data.playerVariables().remove(key);
                }
            }
            case "increment" -> {
                long updated = scopedStateService.incrementVariable(data.playerId(), event, targetContext);
                if (scope.equals("player")) {
                    data.playerVariables().put(key, Long.toString(updated));
                }
            }
            default -> {
                scopedStateService.setVariable(data.playerId(), event, targetContext);
                if (scope.equals("player")) {
                    data.playerVariables().put(key, event.text("value", ""));
                }
            }
        }
    }

    /**
     * Quest-scoped variables live on the player's quest record rather than in the state store,
     * because they are meaningless once the quest ends and should not outlive it.
     */
    private void applyQuestVariable(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            EventDefinition event,
            String key,
            String op) {
        String questId = quest == null
                ? resolve(packageId, event.text("quest", ""))
                : quest.packageId() + ":" + quest.id();
        Map<String, String> variables =
                data.questVariables().computeIfAbsent(questId, ignored -> new ConcurrentHashMap<>());
        switch (op.toLowerCase(Locale.ROOT)) {
            case "remove" -> variables.remove(key);
            case "increment" -> variables.merge(
                    key,
                    Long.toString(event.longValue("amount", 1L)),
                    (current, delta) -> Long.toString(parseLongOrZero(current) + parseLongOrZero(delta)));
            default -> variables.put(key, event.text("value", ""));
        }
    }

    private static long parseLongOrZero(String value) {
        try {
            return value == null || value.isBlank() ? 0L : Long.parseLong(value.trim());
        } catch (NumberFormatException ignored) {
            return 0L;
        }
    }

    /**
     * {@code quest} is a scope only the quest record understands, so it is checked against the raw
     * authored value — normalising it would collapse it onto {@code player} and silently write
     * quest state to the wrong place.
     */
    private static boolean isQuestScope(TypedConfig config) {
        return config.text("scope", "").equalsIgnoreCase("quest");
    }

    private boolean canStart(PlayerQuestData data, QuestDefinition quest) {
        for (ConditionDefinition condition : quest.startConditions()) {
            if (!evaluateCondition(data, quest.packageId(), quest, condition, QuestTargetContext.none())) {
                return false;
            }
        }
        return true;
    }

    private boolean evaluateCondition(PlayerQuestData data, String packageId, QuestDefinition quest, ConditionDefinition condition, QuestTargetContext targetContext) {
        return evaluateCondition(data, packageId, quest, condition, targetContext, new java.util.LinkedHashSet<>());
    }

    private void sendNotification(UUID playerId, String packageId, EventDefinition event) {
        EventDefinition effective = notificationCategory(packageId, event);
        String io = effective.text("io", "notification").toLowerCase();
        if (io.equals("suppress")) {
            return;
        }
        String body = resolveText(playerId, packageId, effective.text("body", effective.text("message", "")));
        if (io.equals("chat") || io.equals("actionbar")) {
            notificationService.sendChat(playerId, body, effective.text("bodyColor", "#EEF3FC"));
            return;
        }
        notificationService.send(playerId, effective, text -> resolveText(playerId, packageId, text));
    }

    private EventDefinition notificationCategory(String packageId, EventDefinition event) {
        EventDefinition merged = new EventDefinition();
        merged.setType("notification");
        String categories = event.text("category", "");
        if (!categories.isBlank()) {
            for (String candidate : categories.split(",")) {
                String key = contentSupplier.get().resolveId(packageId, candidate.trim());
                com.fasterxml.jackson.databind.JsonNode category = contentSupplier.get().notifications().get(key);
                if (category == null) {
                    category = contentSupplier.get().notifications().entrySet().stream()
                            .filter(entry -> entry.getKey().endsWith(":" + candidate.trim()))
                            .map(Map.Entry::getValue).findFirst().orElse(null);
                }
                if (category != null && category.isObject()) {
                    category.properties().forEach(field -> merged.put(field.getKey(), field.getValue()));
                    break;
                }
            }
        }
        event.data().forEach(merged::put);
        return merged;
    }

    private EventDefinition resolveItemEvent(String packageId, EventDefinition event) {
        String raw = event.text("item", "");
        com.fasterxml.jackson.databind.JsonNode item = contentSupplier.get().items()
                .get(contentSupplier.get().resolveId(packageId, raw));
        if (item == null || !item.isObject()) {
            return event;
        }
        EventDefinition resolved = new EventDefinition();
        resolved.setType(event.type());
        item.properties().forEach(field -> resolved.put(field.getKey(), field.getValue()));
        String nativeItem = item.path("item").asText(item.path("id").asText(""));
        resolved.put("item", com.fasterxml.jackson.databind.node.TextNode.valueOf(nativeItem));
        event.data().forEach((key, value) -> {
            if (!key.equals("item")) {
                resolved.put(key, value);
            }
        });
        return resolved;
    }

    private boolean evaluateCondition(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            ConditionDefinition condition,
            QuestTargetContext targetContext,
            Set<String> referenceStack) {
        return switch (condition.type()) {
            // Canonical state types. "invert" carries what the legacy "notTag" alias used to mean.
            case "tag", "globalTag", "entityTag", "blockTag", "volumeTag" ->
                    scopedStateService.hasTag(data.playerId(), packageScopedTag(packageId, condition), targetContext)
                            != condition.bool("invert", false);
            case "notTag" -> !scopedStateService.hasTag(data.playerId(), packageScopedTag(packageId, condition), targetContext);
            case "questCompleted" -> data.completedQuests().containsKey(resolve(packageId, condition.text("quest", "")));
            case "questActive" -> data.activeQuests().containsKey(resolve(packageId, condition.text("quest", "")));
            case "variable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> compareVariable(data, packageId, quest, condition, targetContext);
            case "economy" -> economyBridge.has(data.playerId(), condition.text("account", data.playerId().toString()), condition.decimal("amount", BigDecimal.ZERO));
            case "and", "or", "not" -> composite(data, packageId, quest, condition, targetContext, referenceStack);
            case "ref" -> evaluateConditionReference(data, packageId, quest, condition, targetContext, referenceStack);
            case "permission" -> sessionService.hasPermission(
                    data.playerId(),
                    condition.text("permission", condition.text("node", "")));
            case "inConversation" -> conversationStateSupplier.get().test(data.playerId());
            case "inParty" -> partyMembers.apply(data.playerId()).size() > 1;
            case "partySize" -> compareNumber(
                    partyMembers.apply(data.playerId()).size(),
                    condition.text("operator", condition.text("comparison", ">=")),
                    condition.integer("amount", condition.integer("value", 1)));
            case "playerHidden", "entityHidden" -> isHidden(data.playerId(), condition, targetContext);
            case "targetingPrevented" -> isTargetingPrevented(data.playerId(), condition, targetContext);
            case "nearEntity" -> hasEntityNearby(data.playerId(), condition, targetContext);
            default -> evaluateRegisteredCondition(data.playerId(), packageId, condition, targetContext);
        };
    }

    /** True when {@code target} is currently hidden from {@code viewer}. */
    private boolean isHidden(UUID playerId, ConditionDefinition condition, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            return false;
        }
        UUID viewer = services.targets().resolveSingle(
                viewerSelector(condition), playerId, targetContext);
        UUID subject = services.targets().resolveSingle(condition, playerId, targetContext);
        return viewer != null && subject != null && services.visibility().isHidden(viewer, subject);
    }

    private boolean isTargetingPrevented(UUID playerId, ConditionDefinition condition, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            return false;
        }
        UUID subject = services.targets().resolveSingle(condition, playerId, targetContext);
        return subject != null && services.targeting().isProtected(subject);
    }

    /**
     * True when a tracked entity is within {@code radius} of the player, optionally filtered by an
     * entity-scope {@code tag}. Lets content ask "is a guard watching?" without a custom handler.
     */
    private boolean hasEntityNearby(UUID playerId, ConditionDefinition condition, QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        if (services == null) {
            return false;
        }
        String tag = condition.text("tag", "");
        String radius = Double.toString(condition.decimal(
                "radius", java.math.BigDecimal.valueOf(TargetSelector.DEFAULT_NEAREST_RADIUS)).doubleValue());
        UUID nearest = services.targets().resolveSingle("nearest:" + radius, playerId, targetContext);
        if (nearest == null) {
            return false;
        }
        return tag.isBlank()
                || scopedStateService.store().hasTag(
                        StateKey.of(StateScope.ENTITY, nearest.toString()), tag);
    }

    /** A condition type registered by another mod. An unevaluable gate denies rather than passes. */
    private boolean evaluateRegisteredCondition(
            UUID playerId,
            String packageId,
            ConditionDefinition condition,
            QuestTargetContext targetContext) {
        QuestActionServices services = actionServices;
        QuestConditionHandler handler = services == null ? null : services.registry().condition(condition.type());
        if (handler == null) {
            return false;
        }
        try {
            return handler.test(actionContext(playerId, packageId, condition, targetContext));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception)
                    .log("Registered MysticQuests condition '" + condition.type() + "' failed; denying.");
            return false;
        }
    }

    /** Reads the {@code viewer} field, defaulting to the acting player, matching the hide events. */
    private static String viewerSelector(ConditionDefinition condition) {
        return condition.text("viewer", "self");
    }

    private static boolean compareNumber(long actual, String operator, long expected) {
        return switch (operator) {
            case "=", "==" -> actual == expected;
            case "!=", "<>" -> actual != expected;
            case ">" -> actual > expected;
            case ">=" -> actual >= expected;
            case "<" -> actual < expected;
            case "<=" -> actual <= expected;
            default -> false;
        };
    }

    private boolean evaluateConditionReference(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            ConditionDefinition reference,
            QuestTargetContext targetContext,
            Set<String> stack) {
        LoadedContent content = contentSupplier.get();
        String key = content.resolveId(packageId, reference.text("id", reference.text("ref", "")));
        ConditionDefinition condition = content.conditions().get(key);
        if (condition == null || !stack.add(key)) {
            return false;
        }
        boolean result = evaluateCondition(data, packageOf(key), quest, condition, targetContext, stack);
        stack.remove(key);
        return result;
    }

    private String packageOf(String namespacedId) {
        int separator = namespacedId.lastIndexOf(':');
        return separator < 0 ? namespacedId : namespacedId.substring(0, separator);
    }

    private boolean composite(
            PlayerQuestData data,
            String packageId,
            QuestDefinition quest,
            ConditionDefinition condition,
            QuestTargetContext targetContext,
            Set<String> referenceStack) {
        List<ConditionDefinition> children = children(condition);
        if (children.isEmpty()) {
            // The loader rejects childless composites, so this only happens if content changed
            // underneath a running quest. Deny rather than let an empty gate read as "passed".
            logger.at(Level.WARNING).log("Composite condition '" + condition.type() + "' has no children; denying.");
            return false;
        }
        return switch (condition.type()) {
            case "and" -> children.stream().allMatch(child -> evaluateCondition(data, packageId, quest, child, targetContext, referenceStack));
            case "or" -> children.stream().anyMatch(child -> evaluateCondition(data, packageId, quest, child, targetContext, referenceStack));
            default -> children.stream().noneMatch(child -> evaluateCondition(data, packageId, quest, child, targetContext, referenceStack));
        };
    }

    /** Children of a composite condition. Both {@code conditions} and {@code condition} are accepted. */
    private List<ConditionDefinition> children(ConditionDefinition condition) {
        List<ConditionDefinition> nested = condition.children("conditions", ConditionDefinition::new);
        return nested.isEmpty() ? condition.children("condition", ConditionDefinition::new) : nested;
    }

    private boolean compareVariable(PlayerQuestData data, String packageId, QuestDefinition quest, ConditionDefinition condition, QuestTargetContext targetContext) {
        String key = condition.text("key", condition.text("name", ""));
        String expected = condition.text("value", "");
        String questId = quest == null ? resolve(packageId, condition.text("quest", "")) : quest.packageId() + ":" + quest.id();
        if (isQuestScope(condition)) {
            String actual = data.questVariables().getOrDefault(questId, Map.of()).get(key);
            return expected.equals(actual);
        }
        return scopedStateService.compare(data.playerId(), condition, targetContext);
    }

    private String effectiveScope(EventDefinition event) {
        return switch (event.type()) {
            case "globalTag", "globalVariable" -> "global";
            case "entityTag", "entityVariable" -> "entity";
            case "blockTag", "blockVariable" -> "block";
            case "volumeTag", "volumeVariable" -> "volume";
            default -> ScopedStateService.normalizeScope(event.text("scope", "player"));
        };
    }

    private EventDefinition packageScopedTag(String packageId, EventDefinition source) {
        if (!source.bool("packageScoped", false) || source.text("tag", "").contains(".")) {
            return source;
        }
        EventDefinition copy = new EventDefinition();
        copy.setType(source.type());
        source.data().forEach(copy::put);
        copy.put("tag", com.fasterxml.jackson.databind.node.TextNode.valueOf(
                packageId + "." + source.text("tag", "")));
        return copy;
    }

    private ConditionDefinition packageScopedTag(String packageId, ConditionDefinition source) {
        if (!source.bool("packageScoped", false) || source.text("tag", "").contains(".")) {
            return source;
        }
        ConditionDefinition copy = new ConditionDefinition();
        copy.setType(source.type());
        source.data().forEach(copy::put);
        copy.put("tag", com.fasterxml.jackson.databind.node.TextNode.valueOf(
                packageId + "." + source.text("tag", "")));
        return copy;
    }

    private boolean matchesObjective(ObjectiveDefinition objective, QuestSignal signal) {
        if (!objective.type().equals(signal.type())) {
            return false;
        }
        return switch (objective.type()) {
            case "kill", "gather", "craft", "interactEntity", "interactObject", "dialogue", "triggerEnter", "triggerExit" ->
                    objective.text("target", objective.text("entity", objective.text("item", objective.text("volume", "")))).equals(signal.target());
            // A generated NPC answers to its definition id or to one NPC's stable identity, so a
            // quest can say "talk to any guard" or "talk to this guard" with the same objective.
            case "interactNpc" -> {
                String target = objective.text("target", objective.text("definition", objective.text("npc", "")));
                yield !target.isBlank()
                        && (target.equals(signal.target())
                                || target.equalsIgnoreCase(generationUuidOf(signal)));
            }
            // Narrative transitions advance quests by naming a signal rather than a quest, so the
            // objective must match its own id exactly; unlike "custom", any signal is not enough.
            case "signal" -> objective.text("signal", objective.text("target", "")).equals(signal.target());
            case "reachLocation", "timer", "custom" -> true;
            default -> false;
        };
    }

    private String generationUuidOf(QuestSignal signal) {
        return signal.targetContext() == null ? null : signal.targetContext().generationUuid();
    }

    private boolean isComplete(QuestDefinition quest, ActiveQuestData activeQuest) {
        for (ObjectiveDefinition objective : quest.objectives()) {
            int target = objective.integer("amount", 1);
            if (activeQuest.objectiveProgress().getOrDefault(objective.id(), 0) < target) {
                return false;
            }
        }
        return true;
    }

    private JournalEntry journalEntry(QuestDefinition quest, ActiveQuestData active) {
        if (quest == null || active == null) {
            return null;
        }
        Function<ObjectiveDefinition, ObjectiveView> viewer = objective -> ObjectiveView.of(
                objective.id(),
                objective.displayName(),
                active.objectiveProgress().getOrDefault(objective.id(), 0),
                objective.integer("amount", 1));
        List<ObjectiveView> objectives = quest.objectives().stream().map(viewer).toList();
        return new JournalEntry(
                active.questId(),
                quest.displayName(),
                quest.description(),
                objectives,
                StageView.group(quest.stages(), quest.objectives(), viewer),
                isComplete(quest, active));
    }

    private JournalEntry completedEntry(String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        return JournalEntry.ungrouped(
                questId, quest.displayName(), quest.description(), List.of(ObjectiveView.note("Completed")), true);
    }

    private JournalEntry availableEntry(String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        return JournalEntry.ungrouped(
                questId, quest.displayName(), quest.description(), List.of(ObjectiveView.note("Available to start")), false);
    }

    private JournalEntry abandonedEntry(UUID playerId, String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        ReacceptState reaccept = reacceptState(playerId, questId);
        return JournalEntry.ungrouped(
                questId,
                quest.displayName(),
                quest.description(),
                List.of(ObjectiveView.note(reaccept.allowed() ? "Can be taken again" : reaccept.reason())),
                false);
    }

    private String resolve(String packageId, String id) {
        if (id == null || id.isBlank() || id.contains(":")) {
            return id;
        }
        return packageId + ":" + id;
    }

    private void normalizeTrackedQuest(PlayerQuestData data) {
        if (data.trackedQuestId() != null && data.activeQuests().containsKey(data.trackedQuestId())) {
            return;
        }
        data.setTrackedQuestId(data.activeQuests().values().stream()
                .min(Comparator.comparing(ActiveQuestData::startedAt).thenComparing(ActiveQuestData::questId))
                .map(ActiveQuestData::questId)
                .orElse(null));
    }

    private void save(PlayerQuestData data) {
        try {
            storage.savePlayer(data);
            for (Consumer<UUID> listener : changeListeners) {
                listener.accept(data.playerId());
            }
        } catch (IOException exception) {
            logger.at(Level.WARNING).log("Failed to save quest data for " + data.playerId() + ": " + exception.getMessage());
        }
    }
}
