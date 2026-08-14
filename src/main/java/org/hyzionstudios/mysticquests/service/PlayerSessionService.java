package org.hyzionstudios.mysticquests.service;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.BiConsumer;
import java.util.logging.Level;

/**
 * Single registry of online players. Services that need a {@link PlayerRef}, a permission check, or
 * world-thread access to the player entity resolve it here instead of keeping their own map.
 */
public final class PlayerSessionService {
    private final HytaleLogger logger;
    private final Map<UUID, Session> sessions = new ConcurrentHashMap<>();

    public PlayerSessionService(HytaleLogger logger) {
        this.logger = logger;
    }

    public void register(Ref<EntityStore> playerEntity, Player player) {
        PlayerRef playerRef = null;
        if (playerEntity != null && playerEntity.isValid()) {
            playerRef = playerEntity.getStore().getComponent(playerEntity, PlayerRef.getComponentType());
        }
        if (playerRef == null && player != null) {
            playerRef = player.getPlayerRef();
        }
        if (playerRef == null) {
            return;
        }
        sessions.put(playerRef.getUuid(), new Session(playerRef, player));
    }

    public void unregister(UUID playerId) {
        sessions.remove(playerId);
    }

    public void clear() {
        sessions.clear();
    }

    public PlayerRef playerRef(UUID playerId) {
        Session session = sessions.get(playerId);
        return session == null ? null : session.playerRef();
    }

    public Iterable<UUID> onlinePlayerIds() {
        return sessions.keySet();
    }

    /**
     * Permission check for an online player. Offline players deny, so a quest gated on a permission
     * never opens up just because the holder logged off.
     */
    public boolean hasPermission(UUID playerId, String permission) {
        if (permission == null || permission.isBlank()) {
            return false;
        }
        PlayerRef playerRef = sessions.containsKey(playerId) ? sessions.get(playerId).playerRef() : null;
        return playerRef != null && playerRef.hasPermission(permission);
    }

    /**
     * Runs {@code action} against the player entity on its owning world thread, or immediately when
     * already on that thread. Does nothing when the player is offline or the entity is gone.
     */
    public void runOnWorld(UUID playerId, BiConsumer<Ref<EntityStore>, Store<EntityStore>> action) {
        Session session = sessions.get(playerId);
        if (session == null) {
            return;
        }
        runOnWorld(session, action);
    }

    private void runOnWorld(Session session, BiConsumer<Ref<EntityStore>, Store<EntityStore>> action) {
        try {
            Ref<EntityStore> playerEntity = session.playerRef().getReference();
            if (playerEntity == null || !playerEntity.isValid()) {
                return;
            }
            Store<EntityStore> store = playerEntity.getStore();
            if (store.isInThread()) {
                action.accept(playerEntity, store);
                return;
            }
            World world = store.getExternalData().getWorld();
            world.execute(() -> {
                if (playerEntity.isValid()) {
                    action.accept(playerEntity, store);
                }
            });
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING)
                    .withCause(exception)
                    .log("Failed to schedule MysticQuests world action for " + session.playerRef().getUuid() + ".");
        }
    }

    private record Session(PlayerRef playerRef, Player player) {
    }
}
