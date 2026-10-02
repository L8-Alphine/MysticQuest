package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.UUID;

/**
 * Sends a named signal for the acting player: {@code { "type": "mysticquests:signal", "signal":
 * "hyzion:temple.opened" }}.
 *
 * <p>Signals are how narrative transitions advance quest objectives without knowing which quest is
 * listening. A v1 objective of type {@code signal} with the same id counts it, the same way a
 * {@code triggerEnter} objective counts a volume. Party sharing follows the objective's own
 * {@code shared} setting.
 */
public final class SignalAction implements ActionHandler {
    public static final NamespacedId TYPE = NamespacedId.of("mysticquests", "signal");

    /** Where signals go; the runtime connects this to the quest signal bus. */
    @FunctionalInterface
    public interface Sink {
        void send(UUID player, NamespacedId signal, int amount);
    }

    private final Sink sink;

    private SignalAction(Sink sink) {
        this.sink = sink;
    }

    public static void register(ActionTypeRegistry registry, Sink sink) {
        registry.registerBuiltIn(TYPE, new SignalAction(sink));
    }

    @Override
    public ActionResult execute(ActionContext context, ObjectNode parameters) {
        UUID actor = context.scope().actor();
        if (actor == null) {
            return ActionResult.terminal("signal needs an acting player");
        }
        sink.send(actor, ContentParams.id(parameters, "signal"), Math.max(1, parameters.path("amount").asInt(1)));
        return ActionResult.success();
    }

    @Override
    public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
        ContentParams.id(parameters, "signal", path, report);
        if (parameters.has("amount") && parameters.get("amount").asInt(0) < 1) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "\"amount\" must be a positive integer");
        }
    }
}
