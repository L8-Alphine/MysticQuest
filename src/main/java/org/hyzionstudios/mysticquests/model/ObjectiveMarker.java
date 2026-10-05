package org.hyzionstudios.mysticquests.model;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;
import java.util.Optional;

/**
 * Where an objective happens, for the player's world map: the optional {@code marker} block of an
 * objective.
 *
 * <pre>{@code
 * { "id": "vote_crates", "type": "reachLocation",
 *   "marker": { "world": "default", "x": 120, "y": 64, "z": -88, "label": "Vote Crates" } }
 * }</pre>
 *
 * <p>{@code area: true} marks the centre of a region to search rather than an exact spot, so the
 * player is told to search around it instead of being promised a point. {@code icon} names a file
 * below the client's {@code UI/WorldMap/MapMarkers}; the default is the one vanilla objectives use.
 *
 * @param label what the map calls the marker; empty means "use the objective's name"
 */
public record ObjectiveMarker(String world, double x, double y, double z, String label, boolean area, String icon) {
    /** Vanilla's objective location marker ({@code ReachLocationTask.MARKER_ICON}). */
    public static final String DEFAULT_ICON = "Home.png";

    public ObjectiveMarker {
        world = world == null ? "" : world.trim();
        label = label == null ? "" : label.trim();
        icon = icon == null || icon.isBlank() ? DEFAULT_ICON : icon.trim();
    }

    /** The objective's marker, or empty when it has none or the block is malformed. */
    public static Optional<ObjectiveMarker> of(ObjectiveDefinition objective) {
        try {
            return Optional.ofNullable(parse(objective.data().get("marker")));
        } catch (IllegalArgumentException malformed) {
            return Optional.empty();
        }
    }

    /**
     * Reads a {@code marker} block.
     *
     * @return null when there is no block
     * @throws IllegalArgumentException naming what is wrong with a block that is present
     */
    @Nullable
    public static ObjectiveMarker parse(@Nullable JsonNode node) {
        if (node == null || node.isNull()) {
            return null;
        }
        if (!node.isObject()) {
            throw new IllegalArgumentException("marker must be an object with world, x, y and z");
        }
        JsonNode world = node.get("world");
        if (world == null || !world.isTextual() || world.asText().isBlank()) {
            throw new IllegalArgumentException("marker needs a world name");
        }
        String icon = node.path("icon").asText("").trim();
        if (!icon.isEmpty() && !icon.matches("[A-Za-z0-9_-]+\\.png")) {
            throw new IllegalArgumentException("marker icon must be a file name like " + DEFAULT_ICON);
        }
        return new ObjectiveMarker(
                world.asText(),
                coordinate(node, "x"),
                coordinate(node, "y"),
                coordinate(node, "z"),
                node.path("label").asText(""),
                node.path("area").asBoolean(false),
                icon);
    }

    private static double coordinate(JsonNode node, String axis) {
        JsonNode value = node.get(axis);
        if (value == null || !value.isNumber()) {
            throw new IllegalArgumentException("marker " + axis + " must be a number");
        }
        return value.asDouble();
    }
}
