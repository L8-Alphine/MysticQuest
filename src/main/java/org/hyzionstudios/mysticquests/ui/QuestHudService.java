package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.math.vector.Transform;
import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MapMarkerBuilder;
import com.hypixel.hytale.server.core.universe.world.worldmap.markers.MarkersCollector;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.logging.Level;

/**
 * Owns MysticQuests' two HUD layers — the tracked-quest tracker and the puzzle card — plus the
 * tracked objective's world-map marker, including the grace period after a join.
 *
 * <p>A custom HUD pushed on the ready tick reaches the client while it is still registering the
 * asset pack's UI documents, and the append fails with "Could not find document …" for a document
 * the client already has — which disconnects the player. MysticRPG's working HUD never hits that
 * window because it shows from the callback of an async profile load; this service holds the HUD
 * back explicitly instead of relying on incidental latency.
 *
 * <p>Everything here is presentation. Nothing the service holds — preference, cinematic context, a
 * puzzle card, a marker — feeds back into quest or puzzle state.
 */
public final class QuestHudService {
    /** A solved puzzle's card stays this long, so the player sees the mechanism answer. */
    static final long PUZZLE_COMPLETE_LINGER_MILLIS = 6_000L;
    /** With no puzzle input for this long the player has moved on, and the card goes. */
    static final long PUZZLE_IDLE_MILLIS = 60_000L;
    private static final String MARKER_PROVIDER = "mysticquests_quest_navigation";
    private static final String MARKER_ID = "mysticquests_quest_target";

    private final PlayerQuestService questService;
    private final PlayerSessionService sessionService;
    private final HytaleLogger logger;
    private final boolean enabled;
    private final boolean puzzleCardEnabled;
    private final long joinDelayMillis;
    private final QuestHudCoordinator coordinator = new QuestHudCoordinator();
    private final Map<UUID, QuestHudFocus> focusTargets = new ConcurrentHashMap<>();
    private final Map<UUID, PuzzleCard> puzzleCards = new ConcurrentHashMap<>();
    private final AtomicLong puzzleSerial = new AtomicLong();
    private final ScheduledExecutorService scheduler;
    /** Players still inside the post-join grace period; their HUD updates are held. */
    private final Set<UUID> joining = ConcurrentHashMap.newKeySet();

    /** One published card; the serial tells a stale dismissal timer from a fresh card. */
    private record PuzzleCard(QuestPuzzleHudState state, long serial) {
    }

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
        boolean puzzleDocumentPresent = QuestPuzzleHud.class.getResource(QuestPuzzleHud.DOCUMENT_RESOURCE) != null;
        if (enabled && documentPresent && !puzzleDocumentPresent) {
            logger.at(Level.WARNING).log(
                    "MysticQuests puzzle card disabled: this build does not ship " + QuestPuzzleHud.DOCUMENT + ".");
        }
        this.enabled = enabled && documentPresent;
        this.puzzleCardEnabled = this.enabled && puzzleDocumentPresent;
        this.scheduler = Executors.newSingleThreadScheduledExecutor(runnable -> {
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
        PlayerUiPreferences saved = savedPreferences;
        if (saved != null) {
            coordinator.setPreference(playerId, saved.tracker(playerId));
        }
        joining.add(playerId);
        scheduler.schedule(() -> {
            joining.remove(playerId);
            reconcile(playerId);
        }, joinDelayMillis, TimeUnit.MILLISECONDS);
    }

    public void unregisterPlayer(UUID playerId) {
        joining.remove(playerId);
        coordinator.forget(playerId);
        focusTargets.remove(playerId);
        puzzleCards.remove(playerId);
        withPlayer(playerId, this::removeLayersNow);
    }

    public void close() {
        scheduler.shutdownNow();
        joining.clear();
        coordinator.clear();
        focusTargets.clear();
        puzzleCards.clear();
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
            QuestHudViewModel model = entry == null ? null : QuestHudViewModel.from(entry);
            if (model != null) {
                model = model.withCategory(QuestCategories.badge(
                        questService.definition(entry.questId()).map(QuestDefinition::category).orElse("")));
            }
            QuestHudFocus focus = model == null ? null : focusFor(model);
            publishFocus(playerId, playerRef, focus);
            if (focus != null) {
                model = model.withGuidance(focus.guidanceLabel());
            }
            PuzzleCard card = puzzleCardEnabled ? puzzleCards.get(playerId) : null;
            QuestHudCoordinator.Placement placement = coordinator.resolve(playerId, model, card != null);
            applyTracker(playerRef, player, placement.tracker() ? model.withDisplayMode(placement.mode()) : null);
            applyPuzzleCard(playerRef, player, placement.puzzle() ? card.state() : null);
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to reconcile MysticQuests HUD for " + playerId + ".");
        }
    }

    private void applyTracker(PlayerRef playerRef, Player player, @Nullable QuestHudViewModel model) {
        if (model == null) {
            removeLayer(playerRef, player, QuestHud.KEY);
            return;
        }
        CustomUIHud existing = player.getHudManager().getCustomHud(QuestHud.KEY);
        if (existing instanceof QuestHud questHud) {
            questHud.updateModel(model);
            return;
        }
        player.getHudManager().addCustomHud(playerRef, new QuestHud(playerRef, model));
    }

    private void applyPuzzleCard(PlayerRef playerRef, Player player, @Nullable QuestPuzzleHudState state) {
        if (state == null) {
            removeLayer(playerRef, player, QuestPuzzleHud.KEY);
            return;
        }
        CustomUIHud existing = player.getHudManager().getCustomHud(QuestPuzzleHud.KEY);
        if (existing instanceof QuestPuzzleHud puzzleHud) {
            puzzleHud.updateState(state);
            return;
        }
        player.getHudManager().addCustomHud(playerRef, new QuestPuzzleHud(playerRef, state));
    }

    /** Where tracker preferences are saved; bound once the story-state store exists. */
    private volatile PlayerUiPreferences savedPreferences;

    public void bindPreferences(PlayerUiPreferences preferences) {
        this.savedPreferences = preferences;
    }

    /** Changes the player's presentation preference, saves it, and immediately reconciles the live HUD. */
    public void setPreference(UUID playerId, QuestHudCoordinator.Preference preference) {
        coordinator.setPreference(playerId, preference);
        PlayerUiPreferences saved = savedPreferences;
        if (saved != null) {
            saved.setTracker(playerId, preference);
        }
        reconcile(playerId);
    }

    public QuestHudCoordinator.Preference preference(UUID playerId) {
        return coordinator.preference(playerId);
    }

    /** Publishes temporary gameplay pressure without changing quest state. */
    public void setContext(UUID playerId, QuestHudCoordinator.Context context) {
        coordinator.setContext(playerId, context);
        reconcile(playerId);
    }

    /**
     * Hides or restores the HUD for one named cinematic reason, such as a story cutscene. Independent
     * of {@link #setContext}, so overlapping reasons each have to clear before the HUD returns.
     */
    public void setCinematic(UUID playerId, String source, boolean active) {
        coordinator.setCinematic(playerId, source, active);
        reconcile(playerId);
    }

    /**
     * Publishes a disclosure-safe puzzle snapshot; the server remains the sole source of truth.
     *
     * <p>The card dismisses itself: shortly after the puzzle is solved, or once the player has gone
     * a minute without an input. A newer card cancels an older card's dismissal.
     */
    public void setPuzzleState(UUID playerId, @Nullable QuestPuzzleHudState state) {
        if (state == null) {
            clearPuzzleState(playerId);
            return;
        }
        if (!puzzleCardEnabled) {
            return;
        }
        PuzzleCard card = new PuzzleCard(state, puzzleSerial.incrementAndGet());
        puzzleCards.put(playerId, card);
        long dismissAfter = state.phase() == QuestPuzzleHudState.Phase.COMPLETE
                ? PUZZLE_COMPLETE_LINGER_MILLIS
                : PUZZLE_IDLE_MILLIS;
        scheduler.schedule(() -> {
            if (puzzleCards.remove(playerId, card)) {
                reconcile(playerId);
            }
        }, dismissAfter, TimeUnit.MILLISECONDS);
        reconcile(playerId);
    }

    public void clearPuzzleState(UUID playerId) {
        if (puzzleCards.remove(playerId) != null) {
            reconcile(playerId);
        }
    }

    /** The primary objective's authored marker, if it has one and names a real position. */
    @Nullable
    private QuestHudFocus focusFor(QuestHudViewModel model) {
        if (model.primaryObjectiveId().isEmpty() || model.priorityClass() == QuestHudViewModel.PriorityClass.ACTION_REQUIRED) {
            return null;
        }
        return questService.objectiveMarker(model.questId(), model.primaryObjectiveId())
                .map(marker -> QuestHudFocus.of(marker, model.objectiveText()))
                .filter(QuestHudFocus::hasWorldPosition)
                .orElse(null);
    }

    /**
     * Records the player's marker and makes sure their current world asks for it. The provider is
     * keyed, so registering it again on every reconcile is a map put, and a player who changed world
     * finds it registered in the new one.
     */
    private void publishFocus(UUID playerId, PlayerRef playerRef, @Nullable QuestHudFocus focus) {
        if (focus == null) {
            focusTargets.remove(playerId);
            return;
        }
        focusTargets.put(playerId, focus);
        Ref<EntityStore> reference = playerRef.getReference();
        if (reference != null && reference.isValid()) {
            reference.getStore().getExternalData().getWorld().getWorldMapManager()
                    .addMarkerProvider(MARKER_PROVIDER, this::collectMarker);
        }
    }

    /** Marker provider callback, on the world's thread, once per player the world map updates. */
    @SuppressWarnings("removal")
    private void collectMarker(World world, Player player, MarkersCollector collector) {
        // Player#getUuid is what vanilla's own ObjectiveMarkerProvider#update keys on here.
        UUID playerId = player.getUuid();
        QuestHudFocus focus = playerId == null ? null : focusTargets.get(playerId);
        if (focus == null || !focus.hasWorldPosition() || !world.getName().equalsIgnoreCase(focus.worldName())) {
            return;
        }
        collector.addIgnoreViewDistance(new MapMarkerBuilder(
                MARKER_ID,
                focus.icon(),
                new Transform(focus.x(), focus.y(), focus.z()))
                .withCustomName(focus.markerLabel())
                .build());
    }

    private void removeLayersNow(PlayerRef playerRef, Player player) {
        removeLayer(playerRef, player, QuestHud.KEY);
        removeLayer(playerRef, player, QuestPuzzleHud.KEY);
    }

    /** HudManager#removeCustomHud sends nothing for a key that is not shown. */
    private void removeLayer(PlayerRef playerRef, Player player, String key) {
        try {
            player.getHudManager().removeCustomHud(playerRef, key);
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
