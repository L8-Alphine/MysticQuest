package org.hyzionstudios.mysticquests.studio;

import java.util.List;

/**
 * The result of checking a draft with the same loader and compiler a reload uses (§12.1), so a draft
 * that validates is one the server accepts.
 */
public record StudioValidation(boolean ok, int packages, int quests, List<Problem> problems) {
    /**
     * @param severity {@code error} blocks publishing; {@code warning} does not
     * @param code a diagnostic code such as {@code MISSING_REFERENCE}, or {@code CONTENT} for v1 loader errors
     * @param path where in the content the problem is, when known
     */
    public record Problem(String severity, String code, String path, String message) {
    }

    public StudioValidation {
        problems = List.copyOf(problems);
    }

    /** Checks a draft; implemented by the plugin over the real content loader and narrative compiler. */
    @FunctionalInterface
    public interface Validator {
        StudioValidation validate(ContentRoot draft);
    }

    public long errors() {
        return problems.stream().filter(problem -> problem.severity().equals("error")).count();
    }
}
