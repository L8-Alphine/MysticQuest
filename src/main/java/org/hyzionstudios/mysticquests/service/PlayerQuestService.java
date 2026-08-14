package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.integration.VaultUnlockedEconomyBridge;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;
import org.hyzionstudios.mysticquests.storage.QuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;

import java.io.IOException;
import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;
import java.util.logging.Level;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import java.util.concurrent.ThreadLocalRandom;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandManager;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class PlayerQuestService {
    private static final Pattern SCRIPT_PLACEHOLDER = Pattern.compile("(?<!\\\\)%([^%]+)%");
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
        packetService.clearPlayer(playerId);
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
        packetService.clearPlayer(playerId);
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
        packetService.clearPlayer(data.playerId());
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
                case "addTag", "globalTag", "entityTag", "blockTag", "volumeTag" -> addTag(data, packageId, event, targetContext);
                case "removeTag" -> removeTag(data, packageId, event, targetContext);
                case "setVariable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> setVariable(data, packageId, quest, event, targetContext);
                case "removeVariable" -> scopedStateService.removeVariable(data.playerId(), event, targetContext);
                case "incrementVariable" -> scopedStateService.incrementVariable(data.playerId(), event, targetContext);
                case "startQuest" -> startQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "completeQuest" -> completeQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "modifyMoney" -> economyBridge.modify(data.playerId(), event.text("account", data.playerId().toString()), event.decimal("amount", BigDecimal.ZERO));
                case "packetEffect" -> packetService.rememberEffect(data.playerId(), event.text("effect", event.text("id", "packetEffect")));
                case "triggerHyExtrasEffect" -> hyExtrasBridge.trigger(event.text("action", ""), data.playerId());
                case "notification" -> sendNotification(data.playerId(), packageId, event);
                case "sendMessage" -> notificationService.sendChat(
                        data.playerId(),
                        resolveText(data.playerId(), packageId, event.text("message", event.text("text", ""))),
                        event.text("color", event.text("messageColor", "#EEF3FC")));
                case "giveItem" -> inventoryService.give(data.playerId(), resolveItemEvent(packageId, event));
                case "removeItem" -> inventoryService.remove(data.playerId(), resolveItemEvent(packageId, event));
                case "runCommand" -> runCommand(data.playerId(), event);
                case "cancelConversation" -> conversationCanceller.accept(data.playerId());
                case "cancelQuest" -> cancelQuest(data.playerId(), packageId, event.text("canceler", event.text("id", "")));
                case "folder" -> executeEvents(data, packageId, quest, event.children("events", EventDefinition::new), targetContext, referenceStack);
                case "party" -> executePartyEvents(data.playerId(), packageId, event.children("events", EventDefinition::new), targetContext);
                case "if" -> executeConditional(data, packageId, quest, event, targetContext, referenceStack);
                case "ref" -> executeEventReference(data, packageId, quest, event, targetContext, referenceStack);
                default -> logger.at(Level.WARNING).log("Unknown event type at execution: " + event.type());
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
    private void runCommand(UUID playerId, EventDefinition event) {
        String command = event.text("command", event.text("value", ""));
        if (command.isBlank()) {
            return;
        }
        PlayerRef playerRef = sessionService.playerRef(playerId);
        if (playerRef == null) {
            logger.at(Level.FINE).log("Skipped runCommand for offline player " + playerId + ".");
            return;
        }
        try {
            CommandManager.get().handleCommand(playerRef, command.startsWith("/") ? command.substring(1) : command);
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to run quest command for " + playerId + ".");
        }
    }

    private void addTag(PlayerQuestData data, String packageId, EventDefinition event, QuestTargetContext targetContext) {
        event = packageScopedTag(packageId, event);
        String scope = effectiveScope(event);
        scopedStateService.addTag(data.playerId(), event, targetContext);
        if (scope.equals("player")) {
            data.tags().add(event.text("tag", ""));
        }
    }

    private void removeTag(PlayerQuestData data, String packageId, EventDefinition event, QuestTargetContext targetContext) {
        event = packageScopedTag(packageId, event);
        String scope = effectiveScope(event);
        scopedStateService.removeTag(data.playerId(), event, targetContext);
        if (scope.equals("player")) {
            data.tags().remove(event.text("tag", ""));
        }
    }

    private void setVariable(PlayerQuestData data, String packageId, QuestDefinition quest, EventDefinition event, QuestTargetContext targetContext) {
        String scope = effectiveScope(event);
        String key = event.text("key", event.text("name", ""));
        String value = event.text("value", "");
        if (key.isBlank()) {
            return;
        }
        if (scope.equals("quest")) {
            String questId = quest == null ? resolve(packageId, event.text("quest", "")) : quest.packageId() + ":" + quest.id();
            data.questVariables().computeIfAbsent(questId, ignored -> new ConcurrentHashMap<>()).put(key, value);
        } else {
            scopedStateService.setVariable(data.playerId(), event, targetContext);
            if (scope.equals("player")) {
                data.playerVariables().put(key, value);
            }
        }
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
            case "tag", "globalTag", "entityTag", "blockTag", "volumeTag" -> scopedStateService.hasTag(
                    data.playerId(), packageScopedTag(packageId, condition), targetContext);
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
            default -> false;
        };
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
        String scope = condition.text("scope", "player");
        String questId = quest == null ? resolve(packageId, condition.text("quest", "")) : quest.packageId() + ":" + quest.id();
        if (scope.equals("quest")) {
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
            case "reachLocation", "timer", "custom" -> true;
            default -> false;
        };
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
        List<ObjectiveView> objectives = quest.objectives().stream()
                .map(objective -> ObjectiveView.of(
                        objective.id(),
                        objective.displayName(),
                        active.objectiveProgress().getOrDefault(objective.id(), 0),
                        objective.integer("amount", 1)))
                .toList();
        return new JournalEntry(active.questId(), quest.displayName(), quest.description(), objectives, isComplete(quest, active));
    }

    private JournalEntry completedEntry(String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        return new JournalEntry(questId, quest.displayName(), quest.description(), List.of(ObjectiveView.note("Completed")), true);
    }

    private JournalEntry availableEntry(String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        return new JournalEntry(questId, quest.displayName(), quest.description(), List.of(ObjectiveView.note("Available to start")), false);
    }

    private JournalEntry abandonedEntry(UUID playerId, String questId, QuestDefinition quest) {
        if (quest == null) {
            return null;
        }
        ReacceptState reaccept = reacceptState(playerId, questId);
        return new JournalEntry(
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
