package org.hyzionstudios.mysticquests.packet;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class QuestPacketService {
    private final HytaleLogger logger;
    private final Map<UUID, List<String>> playerEffects = new ConcurrentHashMap<>();

    public QuestPacketService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void rememberEffect(UUID playerId, String effectId) {
        playerEffects.computeIfAbsent(playerId, ignored -> new ArrayList<>()).add(effectId);
        logger.at(Level.FINE).log("Registered quest packet effect " + effectId + " for " + playerId);
    }

    public void clearPlayer(UUID playerId) {
        playerEffects.remove(playerId);
    }

    public void clearAll() {
        playerEffects.clear();
    }
}
