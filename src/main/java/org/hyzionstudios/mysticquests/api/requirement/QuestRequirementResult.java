package org.hyzionstudios.mysticquests.api.requirement;

import java.util.Objects;

/**
 * A provider's answer. Only {@link Status#MET} passes the condition.
 *
 * @param detail a short reason for diagnostics and staff tools (e.g. {@code "level 7 < 10"})
 */
public record QuestRequirementResult(Status status, String detail) {

    public enum Status {
        MET,
        UNMET,
        /** The provider cannot tell (data not loaded, storage down): denies, like UNMET. */
        UNVERIFIABLE
    }

    public QuestRequirementResult {
        Objects.requireNonNull(status, "status");
        detail = detail == null ? "" : detail;
    }

    public static QuestRequirementResult met(String detail) {
        return new QuestRequirementResult(Status.MET, detail);
    }

    public static QuestRequirementResult unmet(String detail) {
        return new QuestRequirementResult(Status.UNMET, detail);
    }

    public static QuestRequirementResult unverifiable(String detail) {
        return new QuestRequirementResult(Status.UNVERIFIABLE, detail);
    }

    public boolean passed() {
        return status == Status.MET;
    }
}
