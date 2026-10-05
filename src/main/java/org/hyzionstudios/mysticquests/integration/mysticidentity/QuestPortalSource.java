package org.hyzionstudios.mysticquests.integration.mysticidentity;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime.Milestone;
import org.hyzionstudios.mysticquests.service.PlayerQuestService.PlayerJournal;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

/**
 * What the web portal may read from MysticQuests, without naming any MysticIdentity type. Every
 * method is read-only and safe off the game thread; none loads a player into the game's caches.
 */
public interface QuestPortalSource {
    /** Whether MysticQuests is running and can answer. */
    boolean available();

    PlayerJournal journal(UUID player) throws IOException;

    /** Story milestones, newest first; empty when the narrative runtime is off. */
    List<Milestone> milestones(UUID player) throws IOException;
}
