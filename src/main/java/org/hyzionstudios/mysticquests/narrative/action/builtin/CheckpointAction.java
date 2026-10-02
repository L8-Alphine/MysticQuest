package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Clock;
import java.util.Optional;

/**
 * Marks a safe point in a story that staff can rewind to (§21.1, §22).
 *
 * <pre>
 *   { "type": "mysticquests:checkpoint", "label": "before_guardian" }
 * </pre>
 *
 * <p>The snapshot holds the session's state, components and ordinary ledger. Rewinding restores it
 * but keeps permanent ledger keys, so items, money and commands are never given twice.
 */
public final class CheckpointAction {
    public static final NamespacedId TYPE = NamespacedId.of("mysticquests", "checkpoint");

    private CheckpointAction() {
    }

    public static void register(ActionTypeRegistry registry, QuestSessionService sessions, Clock clock) {
        registry.registerBuiltIn(TYPE, new Handler(sessions, clock));
    }

    private record Handler(QuestSessionService sessions, Clock clock) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            String sessionId = context.scope().sessionId();
            Optional<QuestSession> session = sessionId == null ? Optional.empty() : sessions.get(sessionId);
            if (session.isEmpty()) {
                return ActionResult.terminal("checkpoint must run inside a story session");
            }
            session.get().checkpoint(parameters.path("label").asText(), clock.instant());
            return ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            if (parameters.path("label").asText("").isBlank()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "checkpoint needs a \"label\"");
            }
        }
    }
}
