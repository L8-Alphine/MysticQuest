package org.hyzionstudios.mysticquests.api;

/**
 * A quest event type contributed by another mod or by a content pack extension.
 *
 * <p>Handlers run synchronously on whatever thread fired the event, usually the world thread mid-tick.
 * Anything slow belongs on the caller's own executor. A handler that throws is logged and skipped;
 * the rest of the event list still runs, so one broken extension cannot strand a quest half-applied.
 */
@FunctionalInterface
public interface QuestEventHandler {
    void execute(QuestActionContext context);
}
