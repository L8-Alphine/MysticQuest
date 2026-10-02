package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.ConversationChoice;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.ConversationNode;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.action.TransitionLedger;
import org.hyzionstudios.mysticquests.narrative.condition.Condition;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionCompiler;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.AudienceMode;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.service.NarrativeScripts;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiFunction;
import java.util.function.Consumer;

/**
 * Runs 2.0 narrative script from v1 quests and conversations: the {@code narrative} event and
 * condition (see {@link NarrativeScripts}).
 *
 * <p>Every script in the content is compiled at reload against the new release, so a typo fails
 * the reload like any other content error. At run time the compiled form is cached per package and
 * text. With a {@code story}, the script runs in that story's session for the player's audience,
 * opening it if needed (conditions never open one); without, it runs for the player alone.
 *
 * <p>A v1 event runs every time it fires, so script actions run every time too: they are not
 * ledgered once-only the way puzzle outputs are.
 */
final class QuestScripts implements NarrativeScripts {
    private static final TransitionLedger EVERY_TIME = new TransitionLedger() {
        @Override
        public boolean applied(String key) {
            return false;
        }

        @Override
        public void record(String key, boolean permanent) {
        }
    };

    private final NarrativeRuntime narrative;
    private final Consumer<String> problems;
    private final Map<String, List<ActionDefinition>> actions = new ConcurrentHashMap<>();
    private final Map<String, Condition> conditions = new ConcurrentHashMap<>();

    QuestScripts(NarrativeRuntime narrative, Consumer<String> problems) {
        this.narrative = narrative;
        this.problems = problems;
    }

    /** Drops compiled scripts after a reload installs new schemas and action types. */
    void clear() {
        actions.clear();
        conditions.clear();
    }

    @Override
    public void run(UUID playerId, String packageId, EventDefinition event, QuestTargetContext target) {
        JsonNode raw = event.data().get("actions");
        List<ActionDefinition> list = actions.computeIfAbsent(packageId + "\u0000" + raw, ignored -> {
            DiagnosticReport report = new DiagnosticReport();
            List<ActionDefinition> compiled = ActionCompiler.compile(raw, packageId + "/narrative",
                    narrative.compileContext(narrative.content(), packageId), report);
            report.errors().forEach(error -> problems.accept("narrative event in " + packageId + ": " + error));
            return report.hasErrors() ? List.of() : compiled;
        });
        if (list.isEmpty()) {
            return;
        }
        String world = target == null ? null : target.worldId();
        QuestSession session = session(playerId, packageId, event.text("story", ""), event.text("audience", "auto"), true);
        ScopeContext scope = scope(playerId, session, world);
        ActionContext context = new ActionContext(scope, "quest:" + packageId);
        String key = "quest-script:" + UUID.randomUUID();
        if (session == null) {
            narrative.executor().run(key, list, context, EVERY_TIME);
        } else {
            synchronized (session) {
                narrative.executor().run(key, list, context, EVERY_TIME);
            }
        }
    }

    @Override
    public boolean test(UUID playerId, String packageId, ConditionDefinition definition) {
        JsonNode raw = definition.data().get("condition");
        Condition condition = conditions.computeIfAbsent(packageId + "\u0000" + raw, ignored -> {
            DiagnosticReport report = new DiagnosticReport();
            Condition compiled = ConditionCompiler.compile(raw, packageId + "/narrative",
                    narrative.compileContext(narrative.content(), packageId), report);
            report.errors().forEach(error -> problems.accept("narrative condition in " + packageId + ": " + error));
            return compiled;
        });
        QuestSession session = session(playerId, packageId, definition.text("story", ""), definition.text("audience", "auto"), false);
        return narrative.conditions().test(condition, scope(playerId, session, null));
    }

    @Nullable
    private QuestSession session(UUID playerId, String packageId, String story, String audience, boolean open) {
        if (story == null || story.isBlank()) {
            return null;
        }
        SessionOwner owner = narrative.audiences().owner(playerId, audience(audience));
        if (owner == null) {
            return null;
        }
        if (open) {
            return narrative.sessions().open(owner, story.trim(), narrative.content().version(packageId));
        }
        Optional<QuestSession> active = narrative.sessions().active(owner, story.trim());
        return active.orElse(null);
    }

    private ScopeContext scope(UUID playerId, @Nullable QuestSession session, @Nullable String world) {
        if (session != null) {
            return narrative.audiences().context(playerId, session, world);
        }
        return new ScopeContext(playerId, null, null, narrative.audiences().party(playerId).orElse(null), world, null, null);
    }

    private static AudienceMode audience(String raw) {
        try {
            return AudienceMode.valueOf(raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            return AudienceMode.AUTO;
        }
    }

    // --- Load-time validation ---

    /** Compiles every {@code narrative} event and condition in v1 content against a new release. */
    static void validate(LoadedContent content, NarrativeContent release, BiFunction<NarrativeContent, String, CompileContext> contexts,
                         DiagnosticReport report) {
        Validator validator = new Validator(release, contexts, report);
        for (QuestDefinition quest : content.quests().values()) {
            String path = quest.packageId() + "/quests<" + quest.id() + ">";
            validator.conditions(quest.startConditions(), quest.packageId(), path + ".startConditions");
            validator.conditions(quest.reacceptConditions(), quest.packageId(), path + ".reacceptConditions");
            validator.events(quest.startEvents(), quest.packageId(), path + ".startEvents");
            validator.events(quest.completeEvents(), quest.packageId(), path + ".completeEvents");
            validator.events(quest.rewards(), quest.packageId(), path + ".rewards");
        }
        content.events().forEach((id, event) -> validator.event(event, packageOf(id), packageOf(id) + "/events<" + id + ">"));
        content.conditions().forEach((id, condition) ->
                validator.condition(condition, packageOf(id), packageOf(id) + "/conditions<" + id + ">"));
        for (ConversationDefinition conversation : content.conversations().values()) {
            String base = conversation.packageId() + "/conversations<" + conversation.id() + ">";
            for (ConversationNode node : conversation.nodes()) {
                validator.conditions(node.conditions(), conversation.packageId(), base + "." + node.id() + ".conditions");
                validator.events(node.events(), conversation.packageId(), base + "." + node.id() + ".events");
                for (ConversationChoice choice : node.choices()) {
                    validator.conditions(choice.conditions(), conversation.packageId(), base + "." + node.id() + ".choice.conditions");
                    validator.events(choice.events(), conversation.packageId(), base + "." + node.id() + ".choice.events");
                }
            }
        }
    }

    private static String packageOf(String namespacedId) {
        int colon = namespacedId.indexOf(':');
        return colon < 0 ? "" : namespacedId.substring(0, colon);
    }

    private record Validator(NarrativeContent release, BiFunction<NarrativeContent, String, CompileContext> contexts,
                             DiagnosticReport report) {
        void events(List<EventDefinition> events, String packageId, String path) {
            for (int index = 0; index < events.size(); index++) {
                event(events.get(index), packageId, path + "[" + index + "]");
            }
        }

        void conditions(List<ConditionDefinition> conditions, String packageId, String path) {
            for (int index = 0; index < conditions.size(); index++) {
                condition(conditions.get(index), packageId, path + "[" + index + "]");
            }
        }

        void event(EventDefinition event, String packageId, String path) {
            switch (event.type()) {
                case TYPE -> {
                    JsonNode raw = event.data().get("actions");
                    if (raw == null || raw.isNull() || raw.isEmpty()) {
                        report.error(DiagnosticCode.INVALID_PARAMETER, path, "a narrative event needs \"actions\"");
                    } else {
                        ActionCompiler.compile(raw, path + ".actions", contexts.apply(release, packageId), report);
                    }
                }
                case "if" -> {
                    conditions(event.children("conditions", ConditionDefinition::new), packageId, path + ".conditions");
                    events(event.children("then", EventDefinition::new), packageId, path + ".then");
                    events(event.children("else", EventDefinition::new), packageId, path + ".else");
                }
                case "folder", "party" -> events(event.children("events", EventDefinition::new), packageId, path + ".events");
                default -> {
                }
            }
        }

        void condition(ConditionDefinition condition, String packageId, String path) {
            switch (condition.type()) {
                case TYPE -> {
                    JsonNode raw = condition.data().get("condition");
                    if (raw == null || !raw.isObject()) {
                        report.error(DiagnosticCode.INVALID_PARAMETER, path, "a narrative condition needs a \"condition\" object");
                    } else {
                        ConditionCompiler.compile(raw, path + ".condition", contexts.apply(release, packageId), report);
                    }
                }
                case "and", "or", "not" -> {
                    conditions(condition.children("conditions", ConditionDefinition::new), packageId, path + ".conditions");
                    conditions(condition.children("condition", ConditionDefinition::new), packageId, path + ".condition");
                }
                default -> {
                }
            }
        }
    }
}
