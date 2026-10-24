package org.hyzionstudios.mysticquests.api.objective;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * Objective tracking for another mod's HUD (MysticRPG PO addendum 2026-10-24, P-62: MysticRPG draws
 * the tracker, MysticQuests owns the quests). Reached through {@code MysticQuestsApi.get().objectives()}
 * since API version 4 (MysticQuests 2.3.0).
 *
 * <p><b>Tracking identity.</b> A player tracks at most one quest, the same one MysticQuests' own
 * tracker and Journal show ({@code trackedQuestId} in the player's quest data). It belongs to the
 * player's quest state, so it is saved with it and survives a reload and a restart; on a network
 * server a trial quest continued from another server (R-1) is tracked again when none is.
 *
 * <p><b>Hidden quests.</b> A quest whose definition says {@code hidden: true} is never exposed here:
 * {@link #tracked} skips it, {@link #active} leaves it out, {@link #track} refuses it with the same
 * message as an unknown quest (so a caller cannot probe for it).
 *
 * <p><b>Changes.</b> {@link #subscribe} reports every change to a player's quests that can change what
 * a tracker shows (progress, start, completion, abandonment, tracking, reload) as a
 * {@link TrackedObjectivesChanged}; the listener reads the new state with {@link #tracked}. Events run
 * on the thread that changed the quest, usually the world thread: keep listeners quick.
 *
 * <p>Reads are safe from any thread and only read state already loaded for online players.
 */
public interface QuestObjectives {

    /** Version of this contract. */
    int API_VERSION = 1;

    default int apiVersion() {
        return API_VERSION;
    }

    /** The quest the player tracks, with its objectives; empty when none (or only hidden quests) are active. */
    Optional<TrackedQuest> tracked(UUID player);

    /** Every active quest the player may track, in the order they started; hidden quests are left out. */
    List<TrackedQuest> active(UUID player);

    /**
     * Tracks one of the player's active quests. Idempotent: tracking the tracked quest again succeeds
     * with {@link TrackingResult#changed()} false.
     */
    TrackingResult track(UUID player, String questId);

    /**
     * Stops tracking {@code questId}. MysticQuests then tracks the player's oldest other active quest,
     * as its own tracker does; untracking a quest that is not tracked changes nothing.
     */
    TrackingResult untrack(UUID player, String questId);

    /** Listens to tracker-relevant changes; close the handle to stop. */
    AutoCloseable subscribe(Consumer<TrackedObjectivesChanged> listener);

    /**
     * Hides MysticQuests' own tracker panel while another mod draws the tracker (one owner per HUD
     * element). The puzzle card and the map marker stay. Close the handle to give the panel back.
     *
     * @param owner who draws the tracker instead, for diagnostics
     */
    AutoCloseable claimTracker(String owner);
}
