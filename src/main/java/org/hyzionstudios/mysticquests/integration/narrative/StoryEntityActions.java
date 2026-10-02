package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;

import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Story entity spawning (§8, §10.2 "Spawn/despawn story entities"), through MysticGeneration.
 *
 * <pre>
 *   { "type": "mysticquests:entity.spawn", "definition": "druid_guardian", "variable": "hyzion:druid_temple.guardian" }
 *   { "type": "mysticquests:entity.despawn", "variable": "hyzion:druid_temple.guardian" }
 * </pre>
 *
 * <p>A spawned NPC is claimed for the running session at once, so it is born isolated: no frame
 * passes in which unrelated players can see or hit it. Its MysticGeneration identity, which
 * survives republishes and restarts, is stored in {@code variable} (an {@code entity}-typed
 * variable) for later steps. Spawning is a permanent step: a checkpoint rollback never spawns a
 * second guardian.
 */
final class StoryEntityActions {
    static final NamespacedId SPAWN = NamespacedId.of("mysticquests", "entity.spawn");
    static final NamespacedId DESPAWN = NamespacedId.of("mysticquests", "entity.despawn");

    private StoryEntityActions() {
    }

    static void register(NarrativeRuntime narrative, MysticGenerationBridge generation, PlayerSessionService players,
                         Consumer<String> problems) {
        narrative.actionTypes().registerBuiltIn(SPAWN, new Spawn(narrative, generation, players, problems));
        narrative.actionTypes().registerBuiltIn(DESPAWN, new Despawn(narrative, generation, players));
    }

    private record Spawn(NarrativeRuntime narrative, MysticGenerationBridge generation, PlayerSessionService players,
                         Consumer<String> problems) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            String sessionId = context.scope().sessionId();
            Optional<QuestSession> session = sessionId == null ? Optional.empty() : narrative.sessions().get(sessionId);
            if (session.isEmpty()) {
                return ActionResult.terminal("entity.spawn must run inside a story session");
            }
            if (generation == null || !generation.available()) {
                return ActionResult.retryable("MysticGeneration is not available");
            }
            UUID actor = context.scope().actor();
            PlayerRef player = actor == null ? null : players.playerRef(actor);
            Ref<EntityStore> body = player == null ? null : player.getReference();
            Transform transform = player == null ? null : player.getTransform();
            if (body == null || !body.isValid() || transform == null) {
                return ActionResult.retryable("the acting player is not in a world");
            }
            Vector3d position = parameters.has("x") && parameters.has("y") && parameters.has("z")
                    ? new Vector3d(parameters.get("x").asDouble(), parameters.get("y").asDouble(), parameters.get("z").asDouble())
                    : new Vector3d(transform.getPosition())
                            .add(new Vector3d(transform.getDirection()).mul(parameters.path("distance").asDouble(2.0)));
            float yaw = (float) parameters.path("yaw").asDouble(transform.getRotation().yaw());
            QuestSession owner = session.get();
            generation.spawn(body.getStore(), parameters.path("definition").asText(), position, yaw, npc -> {
                EntityRefValue entity = new EntityRefValue("generation", npc.uuid().toString());
                narrative.storyEntities().claim(entity, owner.id(), owner.owner(), owner.storyKey());
                if (parameters.hasNonNull("variable")) {
                    StateResult stored = narrative.variables().set(
                            context.scope(), ContentParams.scope(parameters), ContentParams.id(parameters, "variable"), entity);
                    if (stored.rejected()) {
                        problems.accept("entity.spawn could not store " + entity.kind() + ":" + entity.id() + ": " + stored.message());
                    }
                }
            });
            return ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            if (parameters.path("definition").asText("").isBlank()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "entity.spawn needs a MysticGeneration \"definition\"");
            }
            if (parameters.hasNonNull("variable")) {
                checkEntityVariable(parameters, path, context, report);
            }
            if (generation == null || !generation.enabled()) {
                report.warning(DiagnosticCode.MISSING_INTEGRATION, path, "MysticGeneration is disabled; entity.spawn will keep retrying");
            }
        }

        @Override
        public boolean external(ObjectNode parameters) {
            return true;
        }
    }

    private record Despawn(NarrativeRuntime narrative, MysticGenerationBridge generation, PlayerSessionService players)
            implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            EntityRefValue entity = entity(context, parameters);
            if (entity == null) {
                return ActionResult.skipped("no entity to despawn");
            }
            if (!entity.kind().equals("generation")) {
                return ActionResult.terminal("entity.despawn removes MysticGeneration NPCs; got " + entity.kind() + ":" + entity.id());
            }
            UUID actor = context.scope().actor();
            PlayerRef player = actor == null ? null : players.playerRef(actor);
            Ref<EntityStore> body = player == null ? null : player.getReference();
            if (generation == null || !generation.available() || body == null || !body.isValid()) {
                return ActionResult.retryable("MysticGeneration or the acting player is not available");
            }
            generation.despawn(body.getStore(), UUID.fromString(entity.id()));
            narrative.storyEntities().release(entity);
            return ActionResult.success();
        }

        private EntityRefValue entity(ActionContext context, ObjectNode parameters) {
            if (parameters.hasNonNull("entity")) {
                String raw = parameters.get("entity").asText();
                int colon = raw.indexOf(':');
                return colon > 0 ? new EntityRefValue(raw.substring(0, colon), raw.substring(colon + 1)) : null;
            }
            Optional<QuestValue> value = narrative.variables().get(
                    context.scope(), ContentParams.scope(parameters), ContentParams.id(parameters, "variable"));
            return value.isPresent() && value.get() instanceof EntityRefValue ref ? ref : null;
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            if (parameters.hasNonNull("entity") == parameters.hasNonNull("variable")) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "name the entity with exactly one of \"entity\" or \"variable\"");
            } else if (parameters.hasNonNull("variable")) {
                checkEntityVariable(parameters, path, context, report);
            }
        }

        @Override
        public boolean external(ObjectNode parameters) {
            return true;
        }
    }

    private static void checkEntityVariable(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
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
}
