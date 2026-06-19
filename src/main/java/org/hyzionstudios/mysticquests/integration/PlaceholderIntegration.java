package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import at.helpch.placeholderapi.expansion.PlaceholderExpansion;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.UUID;

public final class PlaceholderIntegration {
    private final boolean enabled;
    private final PlayerQuestService questService;
    private PlaceholderExpansion expansion;

    public PlaceholderIntegration(boolean enabled, PlayerQuestService questService) {
        this.enabled = enabled;
        this.questService = questService;
    }

    public void register() {
        if (!enabled) {
            return;
        }
        try {
            expansion = new MysticQuestsExpansion(questService);
            expansion.register();
        } catch (NoClassDefFoundError | Exception ignored) {
            expansion = null;
        }
    }

    public void unregister() {
        if (expansion != null && expansion.isRegistered()) {
            expansion.unregister();
        }
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
