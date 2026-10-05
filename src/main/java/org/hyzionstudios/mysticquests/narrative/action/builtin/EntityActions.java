package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import com.fasterxml.jackson.databind.node.ArrayNode;
import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;
import org.hyzionstudios.mysticquests.narrative.state.QuestVariableService;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Puts entities into a story session, or takes them out (§8).
 *
 * <pre>
 *   { "type": "mysticquests:entity.claim",   "variable": "hyzion:druid_temple.guardian" }
 *   { "type": "mysticquests:entity.claim",   "entity": "uuid:9a3f1c2e-…" }
 *   { "type": "mysticquests:entity.release", "variable": "hyzion:druid_temple.guardian" }
 * </pre>
 *
 * <p>{@code variable} names an {@code entity}-typed variable, usually filled by
 * {@code mysticquests:entity.spawn}. Claiming needs the action to run inside a session; the claim
 * belongs to that session's audience.
 */
public final class EntityActions {
    public static final NamespacedId CLAIM = NamespacedId.of("mysticquests", "entity.claim");
    public static final NamespacedId RELEASE = NamespacedId.of("mysticquests", "entity.release");

    private EntityActions() {
    }

    /**
     * The {@code onDeath} actions of a claim or spawn: run once in the owning session when the entity
     * dies, so rewards for a story boss go to the audience that owns it (§8).
     */
    @Nullable
    public static ArrayNode onDeath(ObjectNode parameters) {
        return parameters.get("onDeath") instanceof ArrayNode actions && !actions.isEmpty() ? actions : null;
    }

    /** Checks {@code onDeath} at load, like any authored action list. */
    public static void validateOnDeath(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
        JsonNode actions = parameters.get("onDeath");
        if (actions == null || actions.isNull()) {
            return;
        }
        if (!actions.isArray()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path + ".onDeath", "onDeath must be a list of actions");
            return;
        }
        ActionCompiler.compile(actions, path + ".onDeath", context, report);
    }

    public static void register(ActionTypeRegistry registry, StoryEntityRegistry entities,
                                QuestSessionService sessions, QuestVariableService variables) {
        registry.registerBuiltIn(CLAIM, new Claim(entities, sessions, variables));
        registry.registerBuiltIn(RELEASE, new Release(entities, variables));
    }

    /** Reads the entity an action names, from {@code entity} or an entity-typed {@code variable}. */
    @Nullable
    static EntityRefValue entity(ActionContext context, ObjectNode parameters, QuestVariableService variables) {
        if (parameters.hasNonNull("entity")) {
            Coercion coerced = ValueCodec.coerce(TypeSpec.of(ValueType.ENTITY_REFERENCE), parameters.get("entity"));
            return coerced.accepted() ? (EntityRefValue) coerced.value() : null;
        }
        Optional<QuestValue> value = variables.get(context.scope(), ContentParams.scope(parameters), ContentParams.id(parameters, "variable"));
        return value.isPresent() && value.get() instanceof EntityRefValue entity ? entity : null;
    }

    /** Load-time checks shared by every action that names an entity. */
    static void validateEntity(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
        boolean literal = parameters.hasNonNull("entity");
        boolean variable = parameters.hasNonNull("variable");
        if (literal == variable) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "name the entity with exactly one of \"entity\" or \"variable\"");
            return;
        }
        if (literal) {
            JsonNode raw = parameters.get("entity");
            Coercion coerced = ValueCodec.coerce(TypeSpec.of(ValueType.ENTITY_REFERENCE), raw);
            if (!coerced.accepted() || !StoryEntityRegistry.supported((EntityRefValue) coerced.value())) {
                report.error(DiagnosticCode.UNKNOWN_ENTITY, path, "\"entity\" must be uuid:<id> or generation:<id>");
            }
            return;
        }
        NamespacedId id = ContentParams.id(parameters, "variable", path, report);
        if (id == null) {
            return;
        }
        VariableSchema schema = context.schemas().variable(id);
        if (schema == null) {
            report.error(DiagnosticCode.UNKNOWN_VARIABLE, path, "variable " + id + " is not declared");
        } else if (schema.type().type() != ValueType.ENTITY_REFERENCE) {
            report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, path, "variable " + id + " must be of type entity, not " + schema.type());
        } else {
            ContentParams.checkScope(parameters, schema.scope(), path, context, report);
        }
    }

    private record Claim(StoryEntityRegistry entities, QuestSessionService sessions, QuestVariableService variables)
            implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            String sessionId = context.scope().sessionId();
            Optional<QuestSession> session = sessionId == null ? Optional.empty() : sessions.get(sessionId);
            if (session.isEmpty()) {
                return ActionResult.terminal("entity.claim must run inside a story session");
            }
            EntityRefValue entity = entity(context, parameters, variables);
            if (entity == null) {
                // Typically the spawn that fills the variable has not finished yet; try again later.
                return ActionResult.retryable("no entity to claim yet");
            }
            if (!StoryEntityRegistry.supported(entity)) {
                return ActionResult.terminal("cannot claim a '" + entity.kind() + "' entity reference");
            }
            if (!entities.admits(entity)) {
                return ActionResult.retryable("the story entity limit (" + entities.claimLimit() + ") is reached");
            }
            entities.claim(entity, session.get().id(), session.get().owner(), session.get().storyKey(), onDeath(parameters));
            return ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            validateEntity(parameters, path, context, report);
            validateOnDeath(parameters, path, context, report);
        }
    }

    private record Release(StoryEntityRegistry entities, QuestVariableService variables) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            EntityRefValue entity = entity(context, parameters, variables);
            if (entity == null) {
                return ActionResult.skipped("no entity to release");
            }
            return entities.release(entity) ? ActionResult.success() : ActionResult.skipped(entity.kind() + ":" + entity.id() + " was not claimed");
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            validateEntity(parameters, path, context, report);
        }
    }
}
