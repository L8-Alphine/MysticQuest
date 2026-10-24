package org.hyzionstudios.mysticquests.api.requirement;

import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

/**
 * One requirement to evaluate.
 *
 * @param playerId   the player the condition runs for
 * @param check      the provider's check name, e.g. {@code level}
 * @param parameters the authored fields of the condition other than {@code type}, {@code provider} and
 *                   {@code check}, as text (arrays joined with commas)
 * @param packageId  the quest package the condition came from
 */
public record QuestRequirementRequest(UUID playerId, String check, Map<String, String> parameters, String packageId) {

    public QuestRequirementRequest {
        Objects.requireNonNull(playerId, "playerId");
        Objects.requireNonNull(check, "check");
        parameters = parameters == null ? Map.of() : Map.copyOf(parameters);
        packageId = packageId == null ? "" : packageId;
    }

    public Optional<String> text(String key) {
        return Optional.ofNullable(parameters.get(key)).filter(value -> !value.isBlank());
    }

    /** A whole-number parameter, or {@code fallback} when absent or not a number. */
    public long number(String key, long fallback) {
        try {
            return text(key).map(String::trim).map(Long::parseLong).orElse(fallback);
        } catch (NumberFormatException notANumber) {
            return fallback;
        }
    }
}
