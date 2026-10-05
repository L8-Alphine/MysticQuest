package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.ObjectiveMarker;

/**
 * Server-issued navigation target for Hytale's native world map.
 *
 * <p>Navigation is the map's job, not a second HUD card: the tracked quest's primary objective, when
 * its author gave it a {@code marker}, is published as a per-player map marker and the tracker's
 * guidance line points at it. An objective without a marker gets no marker — the HUD never invents
 * a position.
 *
 * <p>A search area marks the centre of a region, so its wording says "search around here" rather
 * than promising an exact spot (Redesign Bible §6.3: exact precision only for point targets).
 */
public record QuestHudFocus(
        TargetKind targetKind,
        String worldName,
        String label,
        double x,
        double y,
        double z,
        String icon) {

    public enum TargetKind {
        SEARCH_AREA,
        POINT_TARGET
    }

    public QuestHudFocus {
        targetKind = targetKind == null ? TargetKind.POINT_TARGET : targetKind;
        worldName = clean(worldName);
        label = clean(label).isEmpty() ? "Quest objective" : clean(label);
        icon = clean(icon).isEmpty() ? ObjectiveMarker.DEFAULT_ICON : clean(icon);
    }

    /** The focus for one objective; the marker's own label wins over the objective's name. */
    public static QuestHudFocus of(ObjectiveMarker marker, String objectiveName) {
        return new QuestHudFocus(
                marker.area() ? TargetKind.SEARCH_AREA : TargetKind.POINT_TARGET,
                marker.world(),
                marker.label().isEmpty() ? objectiveName : marker.label(),
                marker.x(),
                marker.y(),
                marker.z(),
                marker.icon());
    }

    public boolean hasWorldPosition() {
        return !worldName.isEmpty() && Double.isFinite(x) && Double.isFinite(y) && Double.isFinite(z);
    }

    /** The name the world map shows beside the marker. */
    public String markerLabel() {
        return targetKind == TargetKind.SEARCH_AREA ? "Search: " + label : label;
    }

    /** The tracker's guidance line while this marker is live. */
    public String guidanceLabel() {
        return targetKind == TargetKind.SEARCH_AREA
                ? "Search the area marked on your map"
                : "Marked on your map: " + label;
    }

    private static String clean(String value) {
        return value == null ? "" : value.trim();
    }
}
