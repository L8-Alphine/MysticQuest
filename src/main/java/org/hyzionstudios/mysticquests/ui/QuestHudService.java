package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class QuestHudService {
    private final PlayerQuestService questService;
    private final HytaleLogger logger;
    private final Map<UUID, OnlinePlayer> onlinePlayers = new ConcurrentHashMap<>();

    public QuestHudService(PlayerQuestService questService, HytaleLogger logger) {
        this.questService = questService;
        this.logger = logger;
    }

    public void registerPlayer(Ref<EntityStore> playerEntity, Player player) {
        Store<EntityStore> store = playerEntity.getStore();
        PlayerRef playerRef = store.getComponent(playerEntity, PlayerRef.getComponentType());
        if (playerRef == null) {
            playerRef = player.getPlayerRef();
        }
        if (playerRef == null) {
            return;
        }
        onlinePlayers.put(playerRef.getUuid(), new OnlinePlayer(playerRef, player));
        reconcile(playerRef.getUuid());
    }

    public void unregisterPlayer(UUID playerId) {
        OnlinePlayer online = onlinePlayers.remove(playerId);
        if (online != null) {
            removeHud(online);
        }
    }

    public void reconcileAll() {
        for (UUID playerId : onlinePlayers.keySet()) {
            reconcile(playerId);
        }
    }

    public void clear() {
        for (OnlinePlayer player : onlinePlayers.values()) {
            removeHud(player);
        }
        onlinePlayers.clear();
    }

    public void reconcile(UUID playerId) {
        OnlinePlayer online = onlinePlayers.get(playerId);
        if (online == null) {
            return;
        }
        try {
            JournalEntry entry = questService.trackedJournalEntry(playerId);
            if (entry == null) {
                removeHud(online);
                return;
            }
            online.player().getHudManager().addCustomHud(online.playerRef(), new QuestHud(online.playerRef(), entry));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to reconcile MysticQuests HUD for " + playerId + ".");
        }
    }

    private void removeHud(OnlinePlayer online) {
        try {
            online.player().getHudManager().removeCustomHud(online.playerRef(), QuestHud.KEY);
        } catch (RuntimeException ignored) {
        }
    }

    private record OnlinePlayer(PlayerRef playerRef, Player player) {
    }
}
