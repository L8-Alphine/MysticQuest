package org.hyzionstudios.mysticquests.integration.external;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.external.ExternalQuestBook;
import org.hyzionstudios.mysticquests.external.ExternalQuestSnapshot;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * One mod whose quests MysticQuests mirrors. Implementations compile against that mod and are only
 * ever loaded by name once it is running ({@link ExternalQuestService#attach}), so nothing else in
 * MysticQuests links to the mod.
 *
 * <p>Every method that takes a {@link PlayerHandle} runs on that player's world thread, where the
 * other mod reads and writes their state itself.
 */
public interface ExternalQuestBridge {
    /** The player's entity on their world thread. */
    record PlayerHandle(Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef, Player player) {
        public UUID id() {
            return playerRef.getUuid();
        }
    }

    /** Publishes a MysticQuests signal for a player: its name and how much it counts. */
    @FunctionalInterface
    interface SignalSink {
        void publish(UUID player, String signal, int amount);
    }

    /** Short id used in quest keys, signals and condition types, such as {@code endgame}. */
    String id();

    /** The mod's name for staff, such as "Endgame &amp; QoL". */
    String displayName();

    /** The keys of the mod's own quest tracker HUD, hidden while MysticQuests' tracker replaces it. */
    Set<String> trackerHudKeys();

    /** What the mod holds for the player right now. Never throws for a player it has not loaded yet. */
    ExternalQuestSnapshot snapshot(PlayerHandle player);

    /** Tracks the quest in the mod too, so its own markers and book agree with the tracker. */
    boolean track(PlayerHandle player, String questId);

    boolean untrack(PlayerHandle player, String questId);

    /** Abandons the quest in the mod; false when the mod does not allow it for this quest. */
    boolean abandon(PlayerHandle player, String questId);

    /** Starts the quest in the mod as if its giver had offered it; false when the mod refuses. */
    boolean accept(PlayerHandle player, String questId);

    /**
     * Adds condition and event types of the mod's own beyond the ones every bridge gets
     * ({@code <id>:questCompleted} and the rest, see {@link ExternalQuestService}). Conditions read
     * {@code book}, which is safe from any thread; event handlers that touch the mod go through
     * {@code onWorld}, which hops to the player's world thread.
     *
     * @return the type names registered, for unregistering on close
     */
    default Set<String> registerContent(MysticQuestsRegistry registry, ExternalQuestBook book, OnWorld onWorld) {
        return Set.of();
    }

    /** Starts forwarding the mod's moments (boss kills, level-ups) as signals; undone by {@link #close}. */
    default void forwardSignals(SignalSink signals) {
    }

    /** Statistics for {@code /mq integrations}: what the mod has loaded. */
    default Map<String, Integer> counts() {
        return Map.of();
    }

    default void close() {
    }

    /** Runs an action on a player's world thread, if they are online. */
    @FunctionalInterface
    interface OnWorld {
        void run(UUID player, Consumer<PlayerHandle> action);
    }
}
