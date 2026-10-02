package org.hyzionstudios.mysticquests.narrative.diagnostic;

import javax.annotation.Nullable;
import java.util.Objects;

/**
 * One actionable problem: what it is, where it is, and when possible how to fix it.
 *
 * @param path where the problem is, as a content path such as
 *         {@code puzzles/hyzion:druid_temple.keys/outputs[2]}
 * @param hint what the author should do about it, or null when the message already says so
 */
public record Diagnostic(Severity severity, DiagnosticCode code, String path, String message, @Nullable String hint) {
    public Diagnostic {
        Objects.requireNonNull(severity, "severity");
        Objects.requireNonNull(code, "code");
        path = path == null ? "" : path;
        Objects.requireNonNull(message, "message");
    }

    public static Diagnostic error(DiagnosticCode code, String path, String message) {
        return new Diagnostic(Severity.ERROR, code, path, message, null);
    }

    public static Diagnostic warning(DiagnosticCode code, String path, String message) {
        return new Diagnostic(Severity.WARNING, code, path, message, null);
    }

    public Diagnostic withHint(String hint) {
        return new Diagnostic(severity, code, path, message, hint);
    }

    @Override
    public String toString() {
        String location = path.isEmpty() ? "" : path + ": ";
        String advice = hint == null ? "" : " (" + hint + ")";
        return severity + " " + code + " " + location + message + advice;
    }
}
