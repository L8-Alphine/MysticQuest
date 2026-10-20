package org.hyzionstudios.mysticquests.integration.worldevents;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * What MysticQuests has seen of Hytale's world events: which are running in which world, where each
 * one has placed its site, which players came close enough to notice it and which took part.
 *
 * <p>Hytale's events have no notion of a player. A goblin breach picks a wilderness spot near a random
 * player ({@code WildernessLocation#find}), records it as a {@code LocationContext} and waits for anyone
 * to walk up; when it finishes it only reports that it ended ({@code EventEndEcsEvent}). This class is
 * the attribution the engine does not do, so a quest can say "you saw the breach" or "you were there
 * when it closed" for one player:
 *
 * <ul>
 *   <li><b>detected</b> — the event has a site and the player came within {@link #DETECTION_RADIUS} of
 *       it. Once per player per site: a breach that restarts somewhere else can be detected again.</li>
 *   <li><b>completed</b> — the event finished, or finished one iteration (the pre-temple breaches repeat
 *       forever), and the player had come within {@link #PARTICIPATION_RADIUS} of its site while it ran.
 *       An event that never placed a site credits nobody: it is indistinguishable from one that gave up
 *       looking for a spot, and nobody can have taken part in that.</li>
 * </ul>
 *
 * <p>Pure bookkeeping with no engine types, so the attribution rules are testable on their own. The
 * world-thread systems in {@code hytale.WorldEventSystems} feed it.
 */
public final class WorldEventTracker {
    /** Blocks from a site at which an event counts as detected; vanilla breaches show their banner to 200. */
    public static final double DETECTION_RADIUS = 96;
    /** Blocks from a site at which a player counts as taking part; vanilla breaches open at 20. */
    public static final double PARTICIPATION_RADIUS = 64;

    public static final String DETECTED = "worldEventDetected";
    public static final String COMPLETED = "worldEventCompleted";

    /** Receives one signal for one player. */
    @FunctionalInterface
    public interface Emitter {
        void emit(UUID player, String signalType, String asset, String world);
    }

    /** A player and where they stand, as read on the world thread. */
    public record PlayerPosition(UUID player, double x, double y, double z) {
    }

    /** Where an event placed its content. */
    public record Site(int x, int y, int z) {
        double distanceSquared(PlayerPosition player) {
            double dx = player.x() - (x + 0.5);
            double dy = player.y() - y;
            double dz = player.z() - (z + 0.5);
            return dx * dx + dy * dy + dz * dz;
        }
    }

    private final Map<String, Tracked> events = new ConcurrentHashMap<>();

    /** An event entity appeared in a world, whether freshly started or reloaded with the world. */
    public void started(String world, UUID eventId, String asset) {
        events.computeIfAbsent(key(world, eventId), ignored -> new Tracked(world, asset));
    }

    /** The event entity is gone: finished, stopped, or unloaded with its world. */
    public void removed(String world, UUID eventId) {
        events.remove(key(world, eventId));
    }

    /**
     * One look at a running event.
     *
     * @param site where it placed its content, or null while it has not placed any
     * @param players every player in the event's world
     */
    public void observe(String world, UUID eventId, String asset, @Nullable Site site,
                        Collection<PlayerPosition> players, Emitter emitter) {
        Tracked tracked = events.computeIfAbsent(key(world, eventId), ignored -> new Tracked(world, asset));
        synchronized (tracked) {
            if (!Objects.equals(tracked.site, site)) {
                // A new site is a new breach: everyone may notice it afresh. Who took part in the old
                // one is kept until the iteration ends, which is when they are credited.
                tracked.site = site;
                tracked.detected.clear();
            }
            if (site == null) {
                return;
            }
            tracked.everPlaced = true;
            double detect = DETECTION_RADIUS * DETECTION_RADIUS;
            double take = PARTICIPATION_RADIUS * PARTICIPATION_RADIUS;
            for (PlayerPosition player : players) {
                double distance = site.distanceSquared(player);
                if (distance <= take) {
                    tracked.participants.add(player.player());
                }
                if (distance <= detect && tracked.detected.add(player.player())) {
                    emitter.emit(player.player(), DETECTED, asset, world);
                }
            }
        }
    }

    /** The event finished, or finished one iteration and restarted from its root. */
    public void ended(String world, UUID eventId, String asset, boolean iteration, Emitter emitter) {
        Tracked tracked = iteration
                ? events.get(key(world, eventId))
                : events.remove(key(world, eventId));
        if (tracked == null) {
            return;
        }
        Set<UUID> credited;
        synchronized (tracked) {
            credited = tracked.everPlaced ? new LinkedHashSet<>(tracked.participants) : Set.of();
            tracked.participants.clear();
            tracked.detected.clear();
            tracked.site = null;
            tracked.everPlaced = false;
        }
        for (UUID player : credited) {
            emitter.emit(player, COMPLETED, asset, world);
        }
    }

    /** Whether an event made from {@code asset} is running in {@code world}. */
    public boolean active(String world, String asset) {
        for (Tracked tracked : events.values()) {
            if (tracked.world.equals(world) && tracked.asset.equals(asset)) {
                return true;
            }
        }
        return false;
    }

    /** Whether an event made from {@code asset} is running in any world. */
    public boolean activeAnywhere(String asset) {
        for (Tracked tracked : events.values()) {
            if (tracked.asset.equals(asset)) {
                return true;
            }
        }
        return false;
    }

    /** Forgets everything, for a world that unloaded or a runtime that is shutting down. */
    public void clear() {
        events.clear();
    }

    private static String key(String world, UUID eventId) {
        return world + '/' + eventId;
    }

    private static final class Tracked {
        final String world;
        final String asset;
        @Nullable Site site;
        /** Whether this iteration ever had a site; a site is cleared again before the iteration ends. */
        boolean everPlaced;
        final Set<UUID> detected = new LinkedHashSet<>();
        final Set<UUID> participants = new LinkedHashSet<>();

        Tracked(String world, String asset) {
            this.world = world;
            this.asset = asset;
        }
    }
}
