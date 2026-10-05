package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * What the Studio reads from, and asks of, the running server (§21): who is online, the runtime's
 * metrics, one player's story state as {@code /mq debug} shows it, and — for the Players page —
 * a player's full quest state and audited changes to it through {@link PlayerStateAdmin}, the same
 * service the in-game admin and {@code /mquest player} use.
 */
public interface StudioLive {
    record OnlinePlayer(UUID id, String name, int activeStories) {
    }

    /** One optional mod or engine feature, as {@code /mq integrations} reports it (§20, §29). */
    record Integration(String name, String state, String detail) {
    }

    /** @param metrics null when the narrative runtime is not running */
    record Overview(List<OnlinePlayer> players, @Nullable NarrativeMetrics.Snapshot metrics, List<Integration> integrations) {
    }

    Overview overview();

    /** Sections in display order, one fact per line, as {@code /mq debug <player>} prints them. */
    Map<String, List<String>> player(UUID player);

    /** A player's quest and story state for the Players page; empty when no server is attached. */
    default Optional<PlayerStateAdmin.Snapshot> playerState(UUID player) {
        return Optional.empty();
    }

    /** An online player by name, or any player by UUID. */
    default Optional<UUID> findPlayer(String nameOrUuid) {
        return Optional.empty();
    }

    /**
     * Applies one staff change to a player where the game would apply it — on their world thread
     * when they are online — and says what happened.
     */
    default PlayerStateAdmin.Outcome changePlayer(UUID player, Function<PlayerStateAdmin, PlayerStateAdmin.Outcome> change) {
        return new PlayerStateAdmin.Outcome(false, "No game server is attached to this Studio.");
    }

    /** For a Studio with no server behind it, such as the development launcher. */
    StudioLive NONE = new StudioLive() {
        @Override
        public Overview overview() {
            return new Overview(List.of(), null, List.of());
        }

        @Override
        public Map<String, List<String>> player(UUID player) {
            return Map.of();
        }
    };
}
