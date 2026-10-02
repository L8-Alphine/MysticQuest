package org.hyzionstudios.mysticquests.narrative.diagnostic;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * Collects {@link Diagnostic}s from a validation or compile pass.
 *
 * <p>Validation reports everything it finds rather than stopping at the first problem, so an author
 * fixes a package in one pass instead of one error per reload.
 */
public final class DiagnosticReport {
    private final List<Diagnostic> diagnostics = new ArrayList<>();

    public DiagnosticReport add(Diagnostic diagnostic) {
        diagnostics.add(diagnostic);
        return this;
    }

    public DiagnosticReport error(DiagnosticCode code, String path, String message) {
        return add(Diagnostic.error(code, path, message));
    }

    public DiagnosticReport error(DiagnosticCode code, String path, String message, String hint) {
        return add(Diagnostic.error(code, path, message).withHint(hint));
    }

    public DiagnosticReport warning(DiagnosticCode code, String path, String message) {
        return add(Diagnostic.warning(code, path, message));
    }

    public DiagnosticReport addAll(DiagnosticReport other) {
        diagnostics.addAll(other.diagnostics);
        return this;
    }

    public boolean hasErrors() {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.severity() == Severity.ERROR);
    }

    public boolean isEmpty() {
        return diagnostics.isEmpty();
    }

    public List<Diagnostic> all() {
        return Collections.unmodifiableList(diagnostics);
    }

    public List<Diagnostic> errors() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Severity.ERROR).toList();
    }

    public List<Diagnostic> warnings() {
        return diagnostics.stream().filter(diagnostic -> diagnostic.severity() == Severity.WARNING).toList();
    }

    /** Whether any diagnostic carries {@code code}; mostly for tests and the debugger. */
    public boolean has(DiagnosticCode code) {
        return diagnostics.stream().anyMatch(diagnostic -> diagnostic.code() == code);
    }

    /** One line per diagnostic, for logs and the reload failure message. */
    public String format() {
        return String.join("\n", diagnostics.stream().map(Diagnostic::toString).toList());
    }
}
