package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import at.helpch.placeholderapi.PlaceholderAPI;
import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;

/**
 * Every reference to PlaceholderAPI is quarantined here so this class — not its caller — is the one
 * that fails to link when the plugin is absent. {@link PlaceholderIntegration} loads it inside a
 * guard, so the resulting {@code NoClassDefFoundError} is caught instead of killing startup.
 */
final class PlaceholderExpansionFactory {
    private PlaceholderExpansionFactory() {
    }

    /** Registers the expansion and returns the callback that removes it again. */
    static Runnable register(PlayerQuestService questService) {
        MysticQuestsExpansion expansion = new MysticQuestsExpansion(questService);
        expansion.register();
        return () -> {
            if (expansion.isRegistered()) {
                expansion.unregister();
            }
        };
    }

    static String resolve(PlayerRef playerRef, String text) {
        return PlaceholderAPI.setPlaceholders(playerRef, text);
    }

    private static final class MysticQuestsExpansion extends PlaceholderExpansion {
        private final PlayerQuestService questService;

        private MysticQuestsExpansion(PlayerQuestService questService) {
            this.questService = questService;
        }

        @Override
        public String getIdentifier() {
            return "mysticquests";
        }

        @Override
        public String getAuthor() {
            return "Hyzion Studios";
        }

        @Override
        public String getVersion() {
            return "1.0.0";
        }

        @Override
        public boolean persist() {
            return true;
        }

        @Override
        public String onPlaceholderRequest(PlayerRef playerRef, String params) {
            if (playerRef == null || params == null) {
                return "";
            }
            UUID playerId = playerRef.getUuid();
            return questService.placeholder(playerId, params);
        }
    }
}
