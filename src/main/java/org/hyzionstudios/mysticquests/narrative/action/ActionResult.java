package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;

import javax.annotation.Nullable;
import java.util.Objects;

/** What an action reports back: its {@link ActionStatus} and, unless it succeeded, why. */
public record ActionResult(ActionStatus status, String message, @Nullable Throwable cause) {
    private static final ActionResult SUCCESS = new ActionResult(ActionStatus.SUCCESS, "", null);

    public ActionResult {
        Objects.requireNonNull(status, "status");
        message = message == null ? "" : message;
    }

    public static ActionResult success() {
        return SUCCESS;
    }

    public static ActionResult skipped(String why) {
        return new ActionResult(ActionStatus.SKIPPED, why, null);
    }

    public static ActionResult retryable(String why) {
        return new ActionResult(ActionStatus.RETRYABLE_FAILURE, why, null);
    }

    public static ActionResult terminal(String why) {
        return new ActionResult(ActionStatus.TERMINAL_FAILURE, why, null);
    }

    public static ActionResult terminal(String why, Throwable cause) {
        return new ActionResult(ActionStatus.TERMINAL_FAILURE, why, cause);
    }

    /**
     * Maps a state write onto an action result. A refusal because the scope has no owner here (a
     * solo player and a party tag) may clear later, so it is retryable; any other refusal is a
     * content error and terminal.
     */
    public static ActionResult of(StateResult result) {
        if (!result.rejected()) {
            return SUCCESS;
        }
        return result.code() == DiagnosticCode.SCOPE_UNRESOLVED
                ? retryable(result.message())
                : terminal(result.message());
    }
}
