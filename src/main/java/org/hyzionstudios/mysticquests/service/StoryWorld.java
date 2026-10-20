package org.hyzionstudios.mysticquests.service;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * Hytale's own story systems as quest actions and conditions see them: world events (the goblin
 * breaches), game flags (the universe-wide campaign progress, {@code goblin_lab_generator_found} and
 * friends) and encounters (boss fights, breach packs).
 *
 * <p>An interface so the quest service stays free of the engine plugins behind it; the runtime binds
 * {@code integration.worldevents.HytaleStoryActions}. Unbound, every action is skipped with a warning
 * and every condition denies.
 */
public interface StoryWorld {
    /** Starts {@code asset} in the player's world, or a global event when {@code global} is set. */
    void startWorldEvent(UUID playerId, @Nullable String asset, @Nullable String global);

    /** Stops {@code asset} in the player's world, or a global event everywhere. */
    void stopWorldEvent(UUID playerId, @Nullable String asset, @Nullable String global);

    /** Sends a world-event signal in the player's world. */
    void signalWorldEvent(UUID playerId, String signalId);

    /** Places an encounter at the player, or at the given position in the player's world. */
    void startEncounter(UUID playerId, String encounterId, @Nullable double[] position);

    int gameFlag(String flag);

    void setGameFlag(String flag, int value, String mode);

    boolean worldEventActive(UUID playerId, String asset, boolean anywhere);
}
