package org.hyzionstudios.mysticquests.api;

import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.service.QuestTargetContext;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.UnaryOperator;

/**
 * Everything a registered event or condition handler is given when it runs.
 *
 * <p>Deliberately does not hand out MysticQuests services: a handler reads and writes state through
 * {@link MysticQuestsApi#get()}, which keeps the extension surface to one stable facade rather than
 * to whatever internals happened to be convenient.
 *
 * @param playerId the player the action is running for
 * @param packageId the quest package the definition came from, for resolving relative ids
 * @param definition the authored JSON for this action, including any custom fields
 * @param target what the action was fired against — entity, block, or trigger volume; never null,
 *         but its fields are null when the trigger carried no such target
 */
public record QuestActionContext(
        UUID playerId,
        String packageId,
        TypedConfig definition,
        QuestTargetContext target,
        UnaryOperator<String> textResolver) {

    /** Resolves MysticQuests percent-placeholders in authored text against this player and package. */
    public String resolve(@Nullable String text) {
        return text == null ? "" : textResolver.apply(text);
    }

    /** Convenience accessor for a string field of the authored definition. */
    public String text(String key, String fallback) {
        return definition.text(key, fallback);
    }

    /** Convenience accessor for a string field with placeholders already resolved. */
    public String resolvedText(String key, String fallback) {
        return resolve(definition.text(key, fallback));
    }
}
