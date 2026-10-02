package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.content.ContentSchema;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionCompiler;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionHandler;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Lets narrative content use every v1 event and condition type (§25: existing content keeps working).
 *
 * <p>In narrative action lists and condition trees, a type without a namespace is a v1 type. It is
 * wrapped into {@code mysticquests:legacy.event} or {@code mysticquests:legacy.condition} and run
 * through {@link PlayerQuestService}, the same path quests use. So a puzzle can reward with
 * {@code giveItem}, announce with {@code sendTitle}, or require {@code questCompleted}, with no new
 * code per type.
 *
 * <h2>What the bridge cannot know</h2>
 *
 * <p>v1 events report nothing back. The bridge therefore returns success once the event has been
 * dispatched. It refuses ahead of time only in the one case it can see: a player-facing event for a
 * player who is offline. That case is retryable, so a reward earned by a party while one member is
 * away is paid when they return, instead of being recorded as paid and lost.
 *
 * <p>Every v1 event is treated as {@code permanent} in the transition ledger: v1 state is not part of
 * the narrative session, so a checkpoint rollback cannot undo it and must not replay it.
 */
public final class LegacyBridge {
    public static final NamespacedId EVENT = ActionCompiler.LEGACY_EVENT;
    public static final NamespacedId CONDITION = ConditionCompiler.LEGACY_CONDITION;

    /** v1 events that do their work against stored data, so they are safe for an offline player. */
    private static final Set<String> OFFLINE_SAFE = Set.of(
            "tag", "variable", "addTag", "removeTag", "globalTag", "entityTag", "blockTag", "volumeTag",
            "setVariable", "removeVariable", "incrementVariable", "globalVariable", "entityVariable",
            "blockVariable", "volumeVariable", "startQuest", "completeQuest", "cancelQuest", "modifyMoney");

    private LegacyBridge() {
    }

    /**
     * @param registry the v1 extension registry; third-party v1 types registered there are accepted too
     * @param online whether a player is connected to this server
     */
    public static void register(NarrativeRuntime narrative, Supplier<PlayerQuestService> quests,
                                MysticQuestsRegistry registry, Predicate<UUID> online) {
        narrative.actionTypes().registerBuiltIn(EVENT, new EventAction(quests, registry, online));
        narrative.conditionTypes().registerBuiltIn(CONDITION, new LegacyCondition(quests, registry));
    }

    private record EventAction(Supplier<PlayerQuestService> quests, MysticQuestsRegistry registry, Predicate<UUID> online)
            implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            UUID player = context.scope().actor();
            if (player == null) {
                return ActionResult.terminal("v1 events need an acting player");
            }
            EventDefinition event = typed(parameters.get("event"), EventDefinition::new, ContentSchema.Kind.EVENT);
            if (!online.test(player) && !OFFLINE_SAFE.contains(event.type())) {
                return ActionResult.retryable("player " + player + " is offline; '" + event.type() + "' will run when they return");
            }
            quests.get().executeEvents(player, parameters.path("package").asText(""), List.of(event), QuestTargetContext.none());
            return ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            String type = parameters.path("event").path("type").asText("");
            if (!PlayerQuestService.BUILT_IN_EVENT_TYPES.contains(type) && registry.event(type) == null) {
                report.error(DiagnosticCode.UNKNOWN_ACTION, path, "unknown v1 event type '" + type + "'",
                        "use a narrative type such as mysticquests:tag.add, or a v1 event such as giveItem");
            }
        }

        @Override
        public boolean external(ObjectNode parameters) {
            return true;
        }
    }

    private record LegacyCondition(Supplier<PlayerQuestService> quests, MysticQuestsRegistry registry) implements ConditionHandler {
        @Override
        public boolean test(ScopeContext context, ObjectNode parameters) {
            UUID player = context.actor();
            if (player == null) {
                return false;
            }
            ConditionDefinition condition = typed(parameters.get("condition"), ConditionDefinition::new, ContentSchema.Kind.CONDITION);
            return quests.get().evaluateCondition(player, parameters.path("package").asText(""), condition);
        }

        @Override
        public void validate(ObjectNode parameters, String path, DiagnosticReport report) {
            String type = parameters.path("condition").path("type").asText("");
            if (!PlayerQuestService.BUILT_IN_CONDITION_TYPES.contains(type) && registry.condition(type) == null) {
                report.error(DiagnosticCode.UNKNOWN_CONDITION, path, "unknown v1 condition type '" + type + "'");
            }
        }
    }

    /** Builds a v1 definition from authored JSON and rewrites legacy aliases, exactly as the v1 loader does. */
    private static <T extends TypedConfig> T typed(JsonNode node, Supplier<T> factory, ContentSchema.Kind kind) {
        T definition = factory.get();
        if (node != null) {
            for (Map.Entry<String, JsonNode> field : node.properties()) {
                if (field.getKey().equals("type")) {
                    definition.setType(field.getValue().asText());
                } else {
                    definition.put(field.getKey(), field.getValue().deepCopy());
                }
            }
        }
        ContentSchema.canonicalize(definition, kind);
        return definition;
    }
}
