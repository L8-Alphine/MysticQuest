package org.hyzionstudios.mysticquests.api.objective;

/**
 * The answer to a track or untrack request.
 *
 * @param success whether the player's tracking is now what was asked
 * @param changed whether anything changed (false for a repeat: idempotent)
 * @param message a player-facing explanation
 */
public record TrackingResult(boolean success, boolean changed, String message) {

    public static TrackingResult done(String message) {
        return new TrackingResult(true, true, message);
    }

    public static TrackingResult unchanged(String message) {
        return new TrackingResult(true, false, message);
    }

    public static TrackingResult refused(String message) {
        return new TrackingResult(false, false, message);
    }
}
