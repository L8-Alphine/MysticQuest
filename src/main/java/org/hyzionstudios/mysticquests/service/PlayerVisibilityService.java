package org.hyzionstudios.mysticquests.service;

import com.fasterxml.jackson.databind.JsonNode;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;
import java.util.logging.Level;

/** Applies condition-driven, per-viewer player visibility rules using Hytale's native hidden-player manager. */
public final class PlayerVisibilityService implements AutoCloseable {
    private final Supplier<LoadedContent> contentSupplier;
    private final PlayerQuestService quests;
    private final PlayerSessionService sessions;
    private final HytaleLogger logger;
    private final Set<Pair> hidden = ConcurrentHashMap.newKeySet();

    public PlayerVisibilityService(
            Supplier<LoadedContent> contentSupplier,
            PlayerQuestService quests,
            PlayerSessionService sessions,
            HytaleLogger logger) {
        this.contentSupplier = contentSupplier;
        this.quests = quests;
        this.sessions = sessions;
        this.logger = logger;
    }

    public void reconcileAll() {
        List<UUID> online = new ArrayList<>();
        sessions.onlinePlayerIds().forEach(online::add);
        for (UUID source : online) {
            for (UUID viewer : online) {
                if (!source.equals(viewer)) {
                    reconcile(source, viewer);
                }
            }
        }
        hidden.removeIf(pair -> {
            if (online.contains(pair.source()) && online.contains(pair.viewer())) return false;
            show(pair);
            return true;
        });
    }

    private void reconcile(UUID source, UUID viewer) {
        Pair pair = new Pair(source, viewer);
        boolean shouldHide = contentSupplier.get().playerHiders().entrySet().stream()
                .anyMatch(entry -> matches(entry.getKey(), entry.getValue(), source, viewer));
        if (shouldHide && hidden.add(pair)) {
            update(pair, true);
        } else if (!shouldHide && hidden.remove(pair)) {
            update(pair, false);
        }
    }

    private boolean matches(String ruleId, JsonNode rule, UUID source, UUID viewer) {
        if (rule == null || !rule.isObject()) return false;
        String packageId = packageOf(ruleId);
        List<ConditionDefinition> sourceConditions = conditions(rule, "sourceConditions", "source");
        List<ConditionDefinition> targetConditions = conditions(rule, "targetConditions", "target");
        return sourceConditions.stream().allMatch(condition -> quests.evaluateCondition(source, packageId, condition))
                && targetConditions.stream().allMatch(condition -> quests.evaluateCondition(viewer, packageId, condition));
    }

    private List<ConditionDefinition> conditions(JsonNode rule, String primary, String fallback) {
        JsonNode value = rule.has(primary) ? rule.get(primary) : rule.get(fallback);
        if (value == null || value.isNull()) return List.of();
        List<ConditionDefinition> result = new ArrayList<>();
        if (value.isArray()) value.forEach(node -> addCondition(result, node));
        else addCondition(result, value);
        return List.copyOf(result);
    }

    private void addCondition(List<ConditionDefinition> result, JsonNode node) {
        ConditionDefinition condition = new ConditionDefinition();
        if (node.isTextual()) {
            condition.setType("ref");
            condition.put("id", node);
        } else if (node.isObject()) {
            condition.setType(node.path("type").asText("ref"));
            node.properties().forEach(field -> {
                if (!field.getKey().equals("type")) condition.put(field.getKey(), field.getValue());
            });
        } else return;
        result.add(condition);
    }

    private void update(Pair pair, boolean hide) {
        sessions.runOnWorld(pair.viewer(), (ignored, store) -> {
            PlayerRef viewer = sessions.playerRef(pair.viewer());
            if (viewer == null) return;
            if (hide) viewer.getHiddenPlayersManager().hidePlayer(pair.source());
            else viewer.getHiddenPlayersManager().showPlayer(pair.source());
        });
    }

    private void show(Pair pair) {
        try {
            update(pair, false);
        } catch (RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Failed to restore player visibility.");
        }
    }

    private static String packageOf(String id) {
        int separator = id.lastIndexOf(':');
        return separator < 0 ? "" : id.substring(0, separator);
    }

    @Override
    public void close() {
        hidden.forEach(this::show);
        hidden.clear();
    }

    private record Pair(UUID source, UUID viewer) { }
}
