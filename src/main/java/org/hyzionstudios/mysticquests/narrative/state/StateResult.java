package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import javax.annotation.Nullable;

/**
 * The outcome of a tag or variable write.
 *
 * <p>v1 writes returned nothing: a write to a mistyped scope or tag "succeeded" into the wrong
 * place. Every narrative write reports whether it changed anything, and why it was refused when it
 * was. That lets an action turn a refusal into a {@code TERMINAL_FAILURE} the author can see.
 *
 * @param value the value after the write, for variable writes; null otherwise
 * @param code why the write was refused; null unless {@link Status#REJECTED}
 */
public record StateResult(Status status, @Nullable QuestValue value, @Nullable DiagnosticCode code, String message) {
    public enum Status { CHANGED, UNCHANGED, REJECTED }

    public static StateResult changed(@Nullable QuestValue value) {
        return new StateResult(Status.CHANGED, value, null, "");
    }

    public static StateResult unchanged(@Nullable QuestValue value) {
        return new StateResult(Status.UNCHANGED, value, null, "");
    }

    public static StateResult rejected(DiagnosticCode code, String message) {
        return new StateResult(Status.REJECTED, null, code, message);
    }

    public boolean rejected() {
        return status == Status.REJECTED;
    }

    public boolean changed() {
        return status == Status.CHANGED;
    }
}
