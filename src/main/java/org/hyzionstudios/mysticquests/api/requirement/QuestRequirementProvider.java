package org.hyzionstudios.mysticquests.api.requirement;

import java.util.Set;

/**
 * Lets another mod answer typed requirements that quests gate on (MysticRPG master bible U-011, U-128:
 * "typed condition bridge with MysticQuests", the RPG to MysticQuests direction): character level,
 * class, class stage, Paragon, profession, and whatever else the provider names.
 *
 * <p>Register one with {@code MysticQuestsApi.get().requirements().register(provider)}. Content then
 * gates on it in any condition list (start conditions, objective gates, conversation branches):
 *
 * <pre>
 *   { "type": "requirement", "provider": "mysticrpg", "check": "level", "min": 10 }
 *   { "type": "mysticrpg:level", "min": 10 }          (shorthand: namespace:check)
 * </pre>
 *
 * <p><b>Fail closed.</b> No provider for the namespace, a provider that is not {@link #available()},
 * an unknown check, an {@link QuestRequirementResult.Status#UNVERIFIABLE} answer or a provider that
 * throws all deny the condition: an unevaluable gate never opens.
 *
 * <p>Called synchronously wherever conditions are evaluated, including hot paths, so it must be cheap,
 * side-effect free, and never block on network I/O.
 */
public interface QuestRequirementProvider {

    /** Version of this SPI. */
    int SPI_VERSION = 1;

    /** The namespace content uses to address this provider, e.g. {@code mysticrpg}. Lower case. */
    String namespace();

    /** Whether the provider can answer right now. */
    boolean available();

    /** The checks this provider answers, e.g. {@code level}, {@code class}. */
    Set<String> checks();

    /** Evaluates one requirement for one player. */
    QuestRequirementResult evaluate(QuestRequirementRequest request);
}
