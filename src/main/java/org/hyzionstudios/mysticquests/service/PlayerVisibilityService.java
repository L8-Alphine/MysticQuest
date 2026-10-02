package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.state.StateScope;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Supplier;

/**
 * Applies content-authored rules that hide players from each other.
 *
 * <p>A rule pairs source conditions with target conditions; when both hold, the source is hidden from
 * the viewer. Evaluating that is not cheap — each side can be an arbitrary condition tree — and the
 * previous implementation re-evaluated every rule for every ordered pair of online players on every
 * quest change and every join and leave. On a busy server that is quadratic work several times a
 * second, nearly all of it recomputing answers that could not have changed.
 *
 * <p>Two things fix that:
 *
 * <ul>
 *   <li><b>Only the player who changed is reconciled.</b> A quest change or a state change affects
 *       the pairs that player participates in, not every pair on the server: linear, not quadratic.</li>
 *   <li><b>Only relevant changes wake it at all.</b> Rules are indexed by the tags and variables their
 *       conditions read, so a tag no rule mentions costs one set lookup and stops there.</li>
 * </ul>
 *
 * <p>Hides are applied through {@link VisibilityService} under {@link VisibilityService.Source#RULE},
 * so a rule releasing its hold never cancels a hide a quest event applied explicitly.
 */
public final class PlayerVisibilityService implements AutoCloseable {
    private final Supplier<LoadedContent> contentSupplier;
    private final PlayerQuestService quests;
    private final PlayerSessionService sessions;
    private final VisibilityService visibility;

    /** Pairs this service currently holds hidden, so it only ever releases its own. */
    private final Set<Pair> held = ConcurrentHashMap.newKeySet();

    /** Tag names any rule reads. A change to anything outside this set cannot affect visibility. */
    private volatile Set<String> watchedTags = Set.of();
    /** Variable names any rule reads. */
    private volatile Set<String> watchedVariables = Set.of();
    /**
     * True when at least one rule depends on something that cannot be indexed — a quest state, a
     * permission, a registered condition. Those rules force a reconcile on any change.
     */
    private volatile boolean hasUnindexableRules = true;

    private final List<AutoCloseable> subscriptions = new ArrayList<>();

    public PlayerVisibilityService(
            Supplier<LoadedContent> contentSupplier,
            PlayerQuestService quests,
            PlayerSessionService sessions,
            VisibilityService visibility,
            MysticQuestsEventBus eventBus) {
        this.contentSupplier = contentSupplier;
        this.quests = quests;
        this.sessions = sessions;
        this.visibility = visibility;
        subscriptions.add(eventBus.subscribe(StateEvents.TagChange.class, this::onTagChange));
        subscriptions.add(eventBus.subscribe(StateEvents.VariableChange.class, this::onVariableChange));
    }

    /**
     * Rebuilds the dependency index and re-evaluates every pair. Called when content is (re)loaded,
     * which is the only time the rules themselves can change.
     */
    public void reloadRules() {
        Set<String> tags = new HashSet<>();
        Set<String> variables = new HashSet<>();
        boolean unindexable = false;
        for (JsonNode rule : contentSupplier.get().playerHiders().values()) {
            if (rule == null || !rule.isObject()) {
                continue;
            }
            for (ConditionDefinition condition : allConditions(rule)) {
                unindexable |= collectDependencies(condition, tags, variables);
            }
        }
        this.watchedTags = Set.copyOf(tags);
        this.watchedVariables = Set.copyOf(variables);
        this.hasUnindexableRules = unindexable;

        // Forget what we were holding before re-evaluating. A reload drops every override in the
        // visibility service, so a pair still recorded here would be treated as already-hidden and
        // never re-applied — the rule would silently stop working until something else changed.
        for (Pair pair : held) {
            visibility.show(pair.viewer(), pair.source(), VisibilityService.Source.RULE);
        }
        held.clear();
        reconcileAll();
    }

    /** Re-evaluates every ordered pair. Only used on reload and shutdown, never on a state change. */
    public void reconcileAll() {
        List<UUID> online = onlinePlayers();
        for (UUID source : online) {
            for (UUID viewer : online) {
                if (!source.equals(viewer)) {
                    reconcile(source, viewer);
                }
            }
        }
        // Release anything still held for a player who has gone offline.
        held.removeIf(pair -> {
            if (online.contains(pair.source()) && online.contains(pair.viewer())) {
                return false;
            }
            visibility.show(pair.viewer(), pair.source(), VisibilityService.Source.RULE);
            return true;
        });
    }

    /**
     * Re-evaluates only the pairs one player participates in, as both the hidden subject and the
     * viewer. This is the path taken by quest and state changes.
     */
    public void reconcilePlayer(UUID player) {
        if (player == null) {
            return;
        }
        for (UUID other : onlinePlayers()) {
            if (other.equals(player)) {
                continue;
            }
            reconcile(player, other);
            reconcile(other, player);
        }
    }

    /** Releases everything held for a player who has disconnected. */
    public void forgetPlayer(UUID player) {
        held.removeIf(pair -> {
            if (!pair.source().equals(player) && !pair.viewer().equals(player)) {
                return false;
            }
            visibility.show(pair.viewer(), pair.source(), VisibilityService.Source.RULE);
            return true;
        });
    }

    private void onTagChange(StateEvents.TagChange change) {
        if (change.scope() != StateScope.PLAYER) {
            return;
        }
        // A CLEAR carries no tag name, so it always counts as relevant.
        if (change.tag() != null && !hasUnindexableRules && !watchedTags.contains(change.tag())) {
            return;
        }
        reconcileOwner(change.owner());
    }

    private void onVariableChange(StateEvents.VariableChange change) {
        if (change.scope() != StateScope.PLAYER) {
            return;
        }
        if (change.key() != null && !hasUnindexableRules && !watchedVariables.contains(change.key())) {
            return;
        }
        reconcileOwner(change.owner());
    }

    private void reconcileOwner(String owner) {
        try {
            reconcilePlayer(UUID.fromString(owner));
        } catch (IllegalArgumentException notAPlayerUuid) {
            // Player-scope owners are always UUIDs; anything else is not a player and cannot match.
        }
    }

    private void reconcile(UUID source, UUID viewer) {
        Pair pair = new Pair(source, viewer);
        boolean shouldHide = contentSupplier.get().playerHiders().entrySet().stream()
                .anyMatch(entry -> matches(entry.getKey(), entry.getValue(), source, viewer));
        if (shouldHide && held.add(pair)) {
            visibility.hide(viewer, source, VisibilityService.Source.RULE);
        } else if (!shouldHide && held.remove(pair)) {
            visibility.show(viewer, source, VisibilityService.Source.RULE);
        }
    }

    private boolean matches(String ruleId, JsonNode rule, UUID source, UUID viewer) {
        if (rule == null || !rule.isObject()) {
            return false;
        }
        String packageId = packageOf(ruleId);
        return conditions(rule, "sourceConditions", "source").stream()
                        .allMatch(condition -> quests.evaluateCondition(source, packageId, condition))
                && conditions(rule, "targetConditions", "target").stream()
                        .allMatch(condition -> quests.evaluateCondition(viewer, packageId, condition));
    }

    private List<ConditionDefinition> allConditions(JsonNode rule) {
        List<ConditionDefinition> all = new ArrayList<>(conditions(rule, "sourceConditions", "source"));
        all.addAll(conditions(rule, "targetConditions", "target"));
        return all;
    }

    /**
     * Records which tags and variables a condition tree reads.
     *
     * @return true when the tree contains something that cannot be indexed, in which case the rule
     *         has to be re-evaluated on any change rather than only on its own inputs
     */
    private boolean collectDependencies(ConditionDefinition condition, Set<String> tags, Set<String> variables) {
        return switch (condition.type()) {
            case "tag" -> {
                tags.add(condition.text("tag", ""));
                yield false;
            }
            case "variable" -> {
                variables.add(condition.text("key", condition.text("name", "")));
                yield false;
            }
            case "and", "or", "not" -> {
                boolean unindexable = false;
                for (ConditionDefinition child : condition.children("conditions", ConditionDefinition::new)) {
                    unindexable |= collectDependencies(child, tags, variables);
                }
                yield unindexable;
            }
            // Quest state, permissions, party size, and registered conditions have no static
            // dependency we can name, so their rules opt out of indexing entirely.
            default -> true;
        };
    }

    private List<ConditionDefinition> conditions(JsonNode rule, String primary, String fallback) {
        JsonNode value = rule.has(primary) ? rule.get(primary) : rule.get(fallback);
        if (value == null || value.isNull()) {
            return List.of();
        }
        List<ConditionDefinition> result = new ArrayList<>();
        if (value.isArray()) {
            value.forEach(node -> addCondition(result, node));
        } else {
            addCondition(result, value);
        }
        return List.copyOf(result);
    }

    private void addCondition(List<ConditionDefinition> result, JsonNode node) {
        ConditionDefinition condition = new ConditionDefinition();
        if (node.isTextual()) {
            condition.setType("ref");
            condition.put("id", node);
        } else if (node.isObject()) {
            condition.setType(node.path("type").asText("ref"));
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (!field.getKey().equals("type")) {
                    condition.put(field.getKey(), field.getValue());
                }
            }
        } else {
            return;
        }
        result.add(condition);
    }

    private List<UUID> onlinePlayers() {
        List<UUID> online = new ArrayList<>();
        sessions.onlinePlayerIds().forEach(online::add);
        return online;
    }

    private static String packageOf(String id) {
        int separator = id.lastIndexOf(':');
        return separator < 0 ? "" : id.substring(0, separator);
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : subscriptions) {
            try {
                subscription.close();
            } catch (Exception ignored) {
                // Unsubscribing cannot fail in practice; nothing useful to do if it somehow does.
            }
        }
        subscriptions.clear();
        for (Pair pair : held) {
            visibility.show(pair.viewer(), pair.source(), VisibilityService.Source.RULE);
        }
        held.clear();
    }

    private record Pair(UUID source, UUID viewer) {
    }
}
