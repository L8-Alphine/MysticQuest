package org.hyzionstudios.mysticquests.narrative.value;

import javax.annotation.Nullable;

/**
 * The outcome of turning authored or stored input into a typed {@link QuestValue}: either the value
 * or the reason it was refused. Refusal is an ordinary result rather than an exception because
 * content validation wants to collect every bad value in a package, not stop at the first.
 */
public record Coercion(@Nullable QuestValue value, @Nullable String problem) {
    public static Coercion ok(QuestValue value) {
        return new Coercion(value, null);
    }

    public static Coercion rejected(String problem) {
        return new Coercion(null, problem);
    }

    public boolean accepted() {
        return value != null;
    }

    /** The value, or an {@link IllegalArgumentException} carrying the refusal reason. */
    public QuestValue orThrow() {
        if (value == null) {
            throw new IllegalArgumentException(problem);
        }
        return value;
    }
}
