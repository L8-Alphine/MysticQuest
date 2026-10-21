package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.GameMode;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.player.SetGameMode;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.entity.entities.player.pages.CustomUIPage;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.World;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Shows the speaking NPC's model live in the dialogue's character card, and gives every client its
 * real game mode back afterwards.
 *
 * <p>The client only draws a prefab preview while it believes it is in Creative, so showing one
 * tells that client alone that it is ({@link PreviewSpoof}); the server keeps and enforces the real
 * mode. {@link #hide} hands the real mode back and is called wherever the card can disappear:
 * the page being dismissed or replaced (also by the death screen and by a player reset, which both
 * tear the page down through {@code PageManager}), a death without a death screen, a world change,
 * and plugin shutdown. A disconnect only forgets the player.
 *
 * <p>On a page's first build the component does not exist on the client yet, so the preview is sent
 * a few times over ({@link #FIRST_SEND_DELAYS_MS}), each send checking it still belongs to the page
 * that is open. One state per player; every method is safe from any thread, and reads of the player
 * entity happen on its world thread.
 */
public final class SpeakerPreviewService {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();
    static final long[] FIRST_SEND_DELAYS_MS = {50L, 150L, 400L};

    private final boolean enabled;
    private final Map<UUID, State> states = new ConcurrentHashMap<>();

    private static final class State {
        final PlayerRef playerRef;
        final PreviewSpoof spoof = new PreviewSpoof();
        SpeakerStage.Capture shown;
        int serial;

        State(PlayerRef playerRef) {
            this.playerRef = playerRef;
        }
    }

    public SpeakerPreviewService(boolean enabled) {
        this.enabled = enabled;
    }

    /** Whether the card shows a live model at all ({@code dialogue.livePreview}). */
    public boolean enabled() {
        return enabled;
    }

    /**
     * Shows {@code capture} for the player. With {@code firstMount} the page is still on its way to
     * the client. A capture already shown is left alone, keeping the angle the player dragged it to.
     * Call on the player's world thread.
     */
    public void show(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store,
                     CustomUIPage page, SpeakerStage.Capture capture, boolean firstMount) {
        if (!enabled || playerRef == null || capture == null || ref == null || !ref.isValid()) {
            return;
        }
        State state = states.computeIfAbsent(playerRef.getUuid(), ignored -> new State(playerRef));
        int mine;
        synchronized (state) {
            if (state.shown == capture) {
                return;
            }
            state.shown = capture;
            mine = ++state.serial;
        }
        BuilderToolPrefabPreview packet = SpeakerStage.packet(capture);
        if (!firstMount) {
            send(playerRef, ref, store, page, state, mine, packet);
            return;
        }
        World world = store.getExternalData().getWorld();
        for (long delay : FIRST_SEND_DELAYS_MS) {
            try {
                // Dropped by the world itself if it stops accepting tasks first.
                world.scheduleAfter(() -> send(playerRef, ref, store, page, state, mine, packet),
                        delay, TimeUnit.MILLISECONDS);
            } catch (RejectedExecutionException shuttingDown) {
                return;
            }
        }
    }

    /**
     * Clears the card and hands the client its real game mode back, read from the player now. Call
     * on the player's world thread.
     */
    public void hide(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store) {
        GameMode current = null;
        if (ref != null && ref.isValid() && store != null && ref.getStore() == store) {
            Player player = store.getComponent(ref, Player.getComponentType());
            current = player == null ? null : player.getGameMode();
        }
        hide(playerRef, current);
    }

    /**
     * Clears the card and hands the client {@code currentReal} back (the remembered mode when
     * null). Does nothing for a player with no preview.
     */
    public void hide(PlayerRef playerRef, GameMode currentReal) {
        if (playerRef == null) {
            return;
        }
        State state = states.get(playerRef.getUuid());
        if (state == null) {
            return;
        }
        states.remove(playerRef.getUuid(), state);
        end(state, currentReal);
    }

    /** Writes under the state's lock, so a send racing a hide can never land after the restore. */
    private static void end(State state, GameMode currentReal) {
        synchronized (state) {
            state.serial++;
            boolean wasShown = state.shown != null;
            state.shown = null;
            GameMode restore = state.spoof.end(currentReal);
            try {
                if (wasShown || restore != null) {
                    state.playerRef.getPacketHandler().writeNoCache(SpeakerStage.clear());
                }
                if (restore != null) {
                    state.playerRef.getPacketHandler().writeNoCache(new SetGameMode(restore));
                }
            } catch (RuntimeException failure) {
                LOGGER.at(Level.WARNING).withCause(failure).log("Could not clear the dialogue model preview.");
            }
        }
    }

    /** Drops a disconnected player's state; their client is gone, so nothing is sent. */
    public void forget(UUID playerId) {
        if (playerId == null) {
            return;
        }
        State state = states.remove(playerId);
        if (state != null) {
            synchronized (state) {
                state.serial++;
                state.shown = null;
                state.spoof.end(null);
            }
        }
    }

    /** Whether the player's client is currently being told it is in Creative. */
    public boolean spoofing(UUID playerId) {
        State state = playerId == null ? null : states.get(playerId);
        if (state == null) {
            return false;
        }
        synchronized (state) {
            return state.spoof.active();
        }
    }

    /** Players still looking at a card get their remembered real mode back; pending sends lapse. */
    public void close() {
        for (State state : states.values()) {
            end(state, null);
        }
        states.clear();
    }

    private void send(PlayerRef playerRef, Ref<EntityStore> ref, Store<EntityStore> store, CustomUIPage page,
                      State state, int mine, BuilderToolPrefabPreview packet) {
        try {
            if (!ref.isValid() || ref.getStore() != store) {
                return;
            }
            Player player = store.getComponent(ref, Player.getComponentType());
            if (player == null || player.getPageManager().getCustomPage() != page) {
                return;
            }
            synchronized (state) {
                if (state.serial != mine || states.get(playerRef.getUuid()) != state) {
                    return;
                }
                GameMode spoof = state.spoof.beforeSend(player.getGameMode());
                if (spoof != null) {
                    playerRef.getPacketHandler().writeNoCache(new SetGameMode(spoof));
                }
                playerRef.getPacketHandler().writeNoCache(packet);
            }
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log("Could not show the dialogue model preview.");
        }
    }
}
