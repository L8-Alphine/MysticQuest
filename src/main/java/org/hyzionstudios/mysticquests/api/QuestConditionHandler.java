package org.hyzionstudios.mysticquests.api;

/**
 * A quest condition type contributed by another mod or by a content pack extension.
 *
 * <p>Handlers run synchronously wherever the condition is evaluated, which includes hot paths such as
 * objective matching, so they should be cheap and side-effect free. A handler that throws is logged
 * and treated as {@code false}: an unevaluable gate denies rather than silently opening.
 */
@FunctionalInterface
public interface QuestConditionHandler {
    boolean test(QuestActionContext context);
}
