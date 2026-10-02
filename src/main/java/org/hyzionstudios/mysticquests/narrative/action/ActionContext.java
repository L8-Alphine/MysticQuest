package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;

import java.util.Objects;

/**
 * What an action runs against.
 *
 * @param scope who is acting and in which session, quest, party and world
 * @param source what started the transition, for example {@code puzzle:hyzion:druid_temple.keys};
 *         recorded as the provenance of any tag the action adds
 */
public record ActionContext(ScopeContext scope, String source) {
    public ActionContext {
        Objects.requireNonNull(scope, "scope");
        source = source == null ? "" : source;
    }
}
