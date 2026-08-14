package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Owns the pinned quest HUD, including the grace period after a join.
 *
 * <p>A custom HUD pushed on the ready tick reaches the client while it is still registering the
 * asset pack's UI documents, and the append fails with "Could not find document …" for a document
 * the client already has — which disconnects the player. MysticRPG's working HUD never hits that
 * window because it shows from the callback of an async profile load; this service holds the HUD
 * back explicitly instead of relying on incidental latency.
 */
public final class QuestHudService {
    private final PlayerQuestService questService;
    private final PlayerSessionService sessionService;
    private final HytaleLogger logger;
    private final boolean enabled;
    private final long joinDelayMillis;
    private final ScheduledExecutorService joinDelay;
    /** Players still inside the post-join grace period; their HUD updates are held. */
    private final Set<UUID> joining = ConcurrentHashMap.newKeySet();

    public QuestHudService(
            PlayerQuestService questService,
            PlayerSessionService sessionService,
            boolean enabled,
            long joinDelayMillis,
            HytaleLogger logger) {
        this.questService = questService;
        this.sessionService = sessionService;
        this.joinDelayMillis = joinDelayMillis;
        this.logger = logger;
        // A HUD append for a document this JAR does not ship can only end one way: the client fails
        // to resolve it and drops the player. Probe builds strip UI documents on purpose, so check
        // rather than trust.
        boolean documentPresent = QuestHud.class.getResource(QuestHud.DOCUMENT_RESOURCE) != null;
        if (enabled && !documentPresent) {
            logger.at(Level.WARNING).log(
                    "MysticQuests HUD disabled: this build does not ship " + QuestHud.DOCUMENT + ".");
        }
        this.enabled = enabled && documentPresent;
        this.joinDelay = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-HUD");
            thread.setDaemon(true);
            return thread;
        });
    }

    /**
     * Shows the HUD once the client has settled after a join. Everything that would push the HUD
     * before then — quests started on join, their change events — is held until the grace period
     * ends, so the first the client sees of this HUD is one append it can resolve.
     */
    public void reconcileAfterJoin(UUID playerId) {
        if (!enabled) {
            return;
        }
        joining.add(playerId);
        joinDelay.schedule(() -> {
            joining.remove(playerId);
            reconcile(playerId);
        }, joinDelayMillis, TimeUnit.MILLISECONDS);
    }

    public void unregisterPlayer(UUID playerId) {
        joining.remove(playerId);
        withPlayer(playerId, this::removeHudNow);
    }

    public void close() {
        joinDelay.shutdownNow();
        joining.clear();
    }

    public void reconcileAll() {
        for (UUID playerId : sessionService.onlinePlayerIds()) {
            reconcile(playerId);
        }
    }

    public void clear() {
        for (UUID playerId : sessionService.onlinePlayerIds()) {
            unregisterPlayer(playerId);
        }
    }

    public void reconcile(UUID playerId) {
        if (!enabled) {
            return;
        }
        if (joining.contains(playerId)) {
            // The scheduled join reconcile reads current state when it runs, so nothing is lost.
            return;
        }
        withPlayer(playerId, (playerRef, player) -> reconcileNow(playerId, playerRef, player));
    }

    private void reconcileNow(UUID playerId, PlayerRef playerRef, Player player) {
        try {
            JournalEntry entry = questService.trackedJournalEntry(playerId);
            if (entry == null) {
                removeHudNow(playerRef, player);
                return;
            }
            CustomUIHud existing = player.getHudManager().getCustomHud(QuestHud.KEY);
            if (existing instanceof QuestHud questHud) {
                questHud.updateEntry(entry);
                return;
            }
            player.getHudManager().addCustomHud(playerRef, new QuestHud(playerRef, entry));
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to reconcile MysticQuests HUD for " + playerId + ".");
        }
    }

    private void removeHudNow(PlayerRef playerRef, Player player) {
        try {
            player.getHudManager().removeCustomHud(playerRef, QuestHud.KEY);
        } catch (RuntimeException ignored) {
        }
    }

    /** Resolves the player on its world thread; does nothing when they are offline. */
    private void withPlayer(UUID playerId, PlayerAction action) {
        PlayerRef playerRef = sessionService.playerRef(playerId);
        if (playerRef == null) {
            return;
        }
        sessionService.runOnWorld(playerId, (Ref<EntityStore> playerEntity, Store<EntityStore> store) -> {
            Player player = store.getComponent(playerEntity, Player.getComponentType());
            if (player != null) {
                action.accept(playerRef, player);
            }
        });
    }

    @FunctionalInterface
    private interface PlayerAction {
        void accept(PlayerRef playerRef, Player player);
    }
}
