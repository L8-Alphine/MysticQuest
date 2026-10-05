package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeMetrics;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * What the Studio's Live Sessions page reads from the running server (§21): who is online, the
 * runtime's metrics, and one player's story state as {@code /mq debug} shows it. Read-only:
 * interventions stay in game, where each one is audited with its target in view.
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
