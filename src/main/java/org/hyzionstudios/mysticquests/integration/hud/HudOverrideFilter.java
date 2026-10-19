package org.hyzionstudios.mysticquests.integration.hud;

import org.hyzionstudios.mysticquests.ui.HiddenHudKeys;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.Packet;
import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.server.core.io.adapter.PacketAdapters;
import com.hypixel.hytale.server.core.io.adapter.PacketFilter;
import com.hypixel.hytale.server.core.io.adapter.PlayerPacketFilter;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.logging.Level;

/**
 * Keeps other mods' custom HUD layers off players' screens without touching those mods.
 *
 * <p>Every custom HUD reaches the client as a {@link CustomHud} packet named by its key: a layer is
 * shown with {@code clear} and its build commands, patched with {@code clear == false}, and removed
 * with {@code clear} and no commands (see {@code CustomUIHud#show}, {@code CustomUIHud#update} and
 * {@code HudManager#removeCustomHud}). This outbound filter drops the first two for hidden keys, so the
 * other mod keeps its HUD object and goes on updating it, and nothing it does can fail for want of it,
 * while the client never draws it. Removals pass: the client accepts a removal for a layer it does not
 * have — Endgame &amp; QoL sends exactly that on its own — and letting them through means a layer shown
 * before the filter was installed is still taken down when its mod drops it.
 *
 * <p>Within one server run the hidden set only grows. Showing a layer again would need the mod to
 * re-send it whole, and a mod that believes its layer is up only sends patches; so a key taken off
 * the list shows again from the player's next join or the next restart.
 *
 * <p>The filter runs on whichever thread writes the packet, so the key set is an immutable value
 * swapped atomically.
 */
public final class HudOverrideFilter {
    private final HytaleLogger logger;
    private volatile HiddenHudKeys hidden = HiddenHudKeys.NONE;
    private PacketFilter registered;

    public HudOverrideFilter(HytaleLogger logger) {
        this.logger = logger;
    }

    /** Hides these keys too, on top of everything already hidden. */
    public synchronized void hide(HiddenHudKeys keys) {
        if (keys.isEmpty()) {
            return;
        }
        hidden = hidden.plus(keys);
        if (registered == null) {
            registered = PacketAdapters.registerOutbound((PlayerPacketFilter) this::drops);
        }
        logger.at(Level.INFO).log("Hiding custom HUD layers: " + String.join(", ", hidden.describe()));
    }

    public HiddenHudKeys hidden() {
        return hidden;
    }

    private boolean drops(PlayerRef player, Packet packet) {
        return drops(hidden, packet);
    }

    /** True to drop the packet: a hidden layer being shown or patched. */
    static boolean drops(HiddenHudKeys hidden, Packet packet) {
        if (!(packet instanceof CustomHud hud) || !hidden.hides(hud.hudId)) {
            return false;
        }
        boolean removal = hud.clear && (hud.commands == null || hud.commands.length == 0);
        return !removal;
    }

    public synchronized void close() {
        if (registered != null) {
            try {
                PacketAdapters.deregisterOutbound(registered);
            } catch (IllegalArgumentException alreadyGone) {
                // Nothing to undo.
            }
            registered = null;
        }
        hidden = HiddenHudKeys.NONE;
    }
}
