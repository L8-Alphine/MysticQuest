package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.protocol.GameMode;

/**
 * Which game mode one client has been told it is in, for the dialogue's live model preview.
 *
 * <p>The client only draws a prefab preview while it believes it is in Creative (vanilla's prefab
 * browser, {@code PrefabPage}, is a Creative tool). So while the preview shows, that one client is
 * told it is in Creative; the server's own game mode never changes and the server still enforces
 * it. This records what the client was told so the real mode can be handed back.
 *
 * <ul>
 *   <li>A player whose real mode is Creative is never told anything.</li>
 *   <li>If the real mode changes while the spoof is on, the server has just told the client the new
 *       mode itself; the next preview send spoofs over the new mode instead of the stale one.</li>
 *   <li>Restoring prefers the player's current real mode over the remembered one.</li>
 * </ul>
 *
 * <p>Not thread-safe: the owner synchronises.
 */
final class PreviewSpoof {
    private GameMode spoofedOver;

    /**
     * Called before each preview send with the player's current real mode.
     *
     * @return the mode to tell the client first ({@link GameMode#Creative}), or null when it
     *         already accepts a preview
     */
    GameMode beforeSend(GameMode real) {
        if (real == null) {
            return null;
        }
        if (real == GameMode.Creative) {
            // Really in Creative now: there is nothing to hand back later.
            spoofedOver = null;
            return null;
        }
        if (real == spoofedOver) {
            return null;
        }
        spoofedOver = real;
        return GameMode.Creative;
    }

    /**
     * Ends the spoof.
     *
     * @param currentReal the player's real mode now, or null when it cannot be read
     * @return the mode to tell the client, or null when nothing was spoofed
     */
    GameMode end(GameMode currentReal) {
        if (spoofedOver == null) {
            return null;
        }
        GameMode restore = currentReal != null ? currentReal : spoofedOver;
        spoofedOver = null;
        return restore;
    }

    boolean active() {
        return spoofedOver != null;
    }
}
