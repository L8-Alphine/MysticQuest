package org.hyzionstudios.mysticquests.packet;

import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.util.ChatColorUtil;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.ClientCameraView;
import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.protocol.packets.camera.SetServerCamera;
import com.hypixel.hytale.protocol.packets.interface_.Notification;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.protocol.packets.interface_.ShowEventTitle;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.util.Locale;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Screen-level packets quests can send: titles, action bars, and camera control.
 *
 * <p>Replaces a placeholder that recorded effect names in a per-player list and never sent anything —
 * and never released the list either, so it grew for the lifetime of the server.
 *
 * <p>Every method is a no-op for an offline player rather than an error, because quest events fire
 * from schedules and party fan-out that legitimately touch players who have just logged off.
 * Authored text goes through {@link ChatColorUtil} so titles honour the same {@code &} colour codes
 * as chat.
 */
public final class QuestPacketService {
    /** Camera modes an authored {@code setCamera} event can request. */
    public enum CameraMode { FIRST_PERSON, THIRD_PERSON, RESET }

    private final PlayerSessionService sessions;
    private final HytaleLogger logger;

    public QuestPacketService(PlayerSessionService sessions, HytaleLogger logger) {
        this.sessions = sessions;
        this.logger = logger;
    }

    /**
     * Shows a title, and optionally a subtitle, in the centre of the player's screen.
     *
     * @return false when the player is offline or the packet could not be sent
     */
    public boolean sendTitle(
            UUID playerId,
            String title,
            @Nullable String subtitle,
            float durationSeconds,
            float fadeInSeconds,
            float fadeOutSeconds,
            String color) {
        PlayerRef playerRef = sessions.playerRef(playerId);
        if (playerRef == null) {
            return false;
        }
        try {
            FormattedMessage primary = formatted(title, color);
            FormattedMessage secondary =
                    subtitle == null || subtitle.isBlank() ? null : formatted(subtitle, color);
            playerRef.getPacketHandler().write(new ShowEventTitle(
                    fadeInSeconds, fadeOutSeconds, durationSeconds, "", false, primary, secondary));
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to send quest title to " + playerId + ".");
            return false;
        }
    }

    /** Shows a transient message in the action-bar style notification slot. */
    public boolean sendActionBar(UUID playerId, String message, String color) {
        PlayerRef playerRef = sessions.playerRef(playerId);
        if (playerRef == null) {
            return false;
        }
        try {
            Notification notification = new Notification();
            notification.message = formatted(message, color);
            notification.style = NotificationStyle.Default;
            playerRef.getPacketHandler().write(notification);
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to send quest action bar to " + playerId + ".");
            return false;
        }
    }

    /**
     * Switches the player's camera, optionally locking it so they cannot change it back — used for
     * cutscene-style quest moments. {@link CameraMode#RESET} always unlocks.
     */
    public boolean setCamera(UUID playerId, CameraMode mode, boolean locked) {
        PlayerRef playerRef = sessions.playerRef(playerId);
        if (playerRef == null) {
            return false;
        }
        try {
            CameraMode resolved = mode == null ? CameraMode.FIRST_PERSON : mode;
            SetServerCamera packet = new SetServerCamera();
            packet.clientCameraView = resolved == CameraMode.THIRD_PERSON
                    ? ClientCameraView.ThirdPerson
                    : ClientCameraView.FirstPerson;
            // Leaving a player locked into a camera they cannot escape is the worst failure mode
            // here, so a reset can never also lock.
            packet.isLocked = locked && resolved != CameraMode.RESET;
            playerRef.getPacketHandler().write(packet);
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to set quest camera for " + playerId + ".");
            return false;
        }
    }

    /** Returns the player to a normal, unlocked first-person camera. */
    public boolean resetCamera(UUID playerId) {
        return setCamera(playerId, CameraMode.RESET, false);
    }

    /** Parses an authored camera mode, defaulting to first person. */
    public static CameraMode parseCameraMode(String raw) {
        if (raw == null || raw.isBlank()) {
            return CameraMode.FIRST_PERSON;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "third", "thirdperson", "third_person" -> CameraMode.THIRD_PERSON;
            case "reset", "default" -> CameraMode.RESET;
            default -> CameraMode.FIRST_PERSON;
        };
    }

    private static FormattedMessage formatted(String text, String color) {
        return ChatColorUtil.message(text == null ? "" : text, color).getFormattedMessage();
    }
}
