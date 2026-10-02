package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;

import javax.annotation.Nullable;

/**
 * The services quest actions need beyond state, bundled so they can be attached after construction.
 *
 * <p>They cannot be constructor-wired: the visibility service needs player sessions, the target
 * selector needs the entity index, and the registry has to exist before content loads — while the
 * quest service itself is built earlier and is depended on by several of them. The existing
 * {@code bindConversationSupport} and {@code bindPartySupport} hooks solve the same problem the same
 * way; this keeps one bundle rather than adding four more setters.
 */
public record QuestActionServices(
        VisibilityService visibility,
        TargetingPreventionService targeting,
        TargetSelector targets,
        MysticQuestsRegistry registry,
        @Nullable MysticGenerationBridge generation) {

    /** For servers and tests without the optional MysticGeneration bridge. */
    public QuestActionServices(
            VisibilityService visibility,
            TargetingPreventionService targeting,
            TargetSelector targets,
            MysticQuestsRegistry registry) {
        this(visibility, targeting, targets, registry, null);
    }
}
