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
import java.util.function.Supplier;
import java.util.logging.Level;

import com.hypixel.hytale.logger.HytaleLogger;

public final class PlayerQuestService {
    private final Supplier<LoadedContent> contentSupplier;
    private final QuestStorage storage;
    private final ScopedStateService scopedStateService;
    private final QuestPacketService packetService;
    private final VaultUnlockedEconomyBridge economyBridge;
    private final HyExtrasBridge hyExtrasBridge;
    private final QuestNotificationService notificationService;
    private final HytaleLogger logger;
    private final Map<UUID, PlayerQuestData> cache = new ConcurrentHashMap<>();
    private final Set<UUID> migratedPlayers = ConcurrentHashMap.newKeySet();
    private final List<Consumer<UUID>> changeListeners = new CopyOnWriteArrayList<>();

    public PlayerQuestService(
            Supplier<LoadedContent> contentSupplier,
            QuestStorage storage,
            ScopedStateService scopedStateService,
            QuestPacketService packetService,
            VaultUnlockedEconomyBridge economyBridge,
            HyExtrasBridge hyExtrasBridge,
            QuestNotificationService notificationService,
            HytaleLogger logger) {
        this.contentSupplier = contentSupplier;
        this.storage = storage;
        this.scopedStateService = scopedStateService;
        this.packetService = packetService;
        this.economyBridge = economyBridge;
        this.hyExtrasBridge = hyExtrasBridge;
        this.notificationService = notificationService;
        this.logger = logger;
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
        PlayerQuestData data = data(signal.playerId());
        LoadedContent content = contentSupplier.get();
        boolean changed = false;
        for (ActiveQuestData activeQuest : new ArrayList<>(data.activeQuests().values())) {
            QuestDefinition quest = content.quests().get(activeQuest.questId());
            if (quest == null) {
                continue;
            }
            for (ObjectiveDefinition objective : quest.objectives()) {
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
        for (EventDefinition event : events) {
            switch (event.type()) {
                case "addTag", "globalTag", "entityTag", "blockTag", "volumeTag" -> addTag(data, event, targetContext);
                case "removeTag" -> removeTag(data, event, targetContext);
                case "setVariable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> setVariable(data, packageId, quest, event, targetContext);
                case "removeVariable" -> scopedStateService.removeVariable(data.playerId(), event, targetContext);
                case "incrementVariable" -> scopedStateService.incrementVariable(data.playerId(), event, targetContext);
                case "startQuest" -> startQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "completeQuest" -> completeQuest(data.playerId(), resolve(packageId, event.text("quest", "")));
                case "modifyMoney" -> economyBridge.modify(data.playerId(), event.text("account", data.playerId().toString()), event.decimal("amount", BigDecimal.ZERO));
                case "packetEffect" -> packetService.rememberEffect(data.playerId(), event.text("effect", event.text("id", "packetEffect")));
                case "triggerHyExtrasEffect" -> hyExtrasBridge.trigger(event.text("action", ""), data.playerId());
                case "notification" -> notificationService.send(data.playerId(), event);
                case "folder", "if", "runForAll", "runIndependent", "cancelConversation", "hidePlayer", "showPlayer",
                        "sendMessage", "giveItem", "removeItem", "runCommand", "custom" -> logger.at(Level.INFO).log("Queued event " + event.type() + " for " + data.playerId());
                default -> logger.at(Level.WARNING).log("Unknown event type at execution: " + event.type());
            }
        }
    }

    private void addTag(PlayerQuestData data, EventDefinition event, QuestTargetContext targetContext) {
        String scope = effectiveScope(event);
        scopedStateService.addTag(data.playerId(), event, targetContext);
        if (scope.equals("player")) {
            data.tags().add(event.text("tag", ""));
        }
    }

    private void removeTag(PlayerQuestData data, EventDefinition event, QuestTargetContext targetContext) {
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

    private boolean evaluateCondition(PlayerQuestData data, QuestDefinition quest, ConditionDefinition condition) {
        return evaluateCondition(data, quest.packageId(), quest, condition, QuestTargetContext.none());
    }

    private boolean evaluateCondition(PlayerQuestData data, String packageId, QuestDefinition quest, ConditionDefinition condition, QuestTargetContext targetContext) {
        return switch (condition.type()) {
            case "tag", "globalTag", "entityTag", "blockTag", "volumeTag" -> scopedStateService.hasTag(data.playerId(), condition, targetContext);
            case "questCompleted" -> data.completedQuests().containsKey(resolve(packageId, condition.text("quest", "")));
            case "questActive" -> data.activeQuests().containsKey(resolve(packageId, condition.text("quest", "")));
            case "variable", "globalVariable", "entityVariable", "blockVariable", "volumeVariable" -> compareVariable(data, packageId, quest, condition, targetContext);
            case "economy" -> economyBridge.has(data.playerId(), condition.text("account", data.playerId().toString()), condition.decimal("amount", BigDecimal.ZERO));
            case "and" -> true;
            case "or" -> true;
            case "not" -> false;
            case "playerHidden", "inConversation", "permission", "level", "custom" -> true;
            default -> false;
        };
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
        if (quest == null) {
            return null;
        }
        List<String> objectives = quest.objectives().stream()
                .map(objective -> objective.displayName() + ": " + active.objectiveProgress().getOrDefault(objective.id(), 0) + "/" + objective.integer("amount", 1))
                .toList();
        return new JournalEntry(active.questId(), quest.displayName(), quest.description(), objectives, isComplete(quest, active));
    }

    private String resolve(QuestDefinition quest, String id) {
        return resolve(quest.packageId(), id);
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
