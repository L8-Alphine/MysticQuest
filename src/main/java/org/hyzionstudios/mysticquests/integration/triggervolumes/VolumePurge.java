package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Consumer;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Removes the copies of a trigger volume that piled up under one name. The engine names a second copy
 * {@code name (2)}, a third {@code name (3)} and so on, and its own {@code /tv remove} takes one volume
 * id at a time, so thousands of copies left by a world event or a paste loop could not be cleared from
 * the game.
 *
 * <p>Copies are flagged for destruction, as a world event's {@code TriggerVolumeRemove} action flags
 * its volume: the engine drops them at the end of the next tick, cancels their delayed effects and
 * updates anyone viewing volumes.
 */
public final class VolumePurge {
    private static final Pattern NUMBERED = Pattern.compile("(.*) \\((\\d+)\\)");

    private VolumePurge() {
    }

    /** One volume as the purge sees it: the engine id, the name, and the block it sits on. */
    public record Candidate(String id, String name, String block) {
    }

    /**
     * What a purge keeps and what it removes.
     *
     * @param positions on how many distinct blocks the copies sit; 1 means they are stacked in one place
     */
    public record Plan(List<Candidate> kept, List<Candidate> removed, int positions) {
        public int total() {
            return kept.size() + removed.size();
        }
    }

    /** Whether {@code name} is {@code base} or one of its numbered copies, ignoring case. */
    static boolean matches(String base, @Nullable String name) {
        if (name == null) {
            return false;
        }
        if (name.equalsIgnoreCase(base)) {
            return true;
        }
        Matcher numbered = NUMBERED.matcher(name);
        return numbered.matches() && numbered.group(1).equalsIgnoreCase(base);
    }

    /** 1 for the plain name, N for {@code name (N)}. */
    static long copyNumber(String name) {
        Matcher numbered = NUMBERED.matcher(name);
        if (!numbered.matches()) {
            return 1;
        }
        try {
            return Long.parseLong(numbered.group(2));
        } catch (NumberFormatException tooLong) {
            return Long.MAX_VALUE;
        }
    }

    /**
     * Keeps the first {@code keep} volumes named {@code base}, the plain name before the lowest copy
     * numbers, and removes the rest.
     */
    static Plan plan(String base, Collection<Candidate> volumes, int keep) {
        List<Candidate> named = new ArrayList<>();
        Set<String> blocks = new HashSet<>();
        for (Candidate volume : volumes) {
            if (matches(base, volume.name())) {
                named.add(volume);
                blocks.add(volume.block());
            }
        }
        named.sort(Comparator.comparingLong((Candidate volume) -> copyNumber(volume.name()))
                .thenComparing(Candidate::id));
        int kept = Math.min(Math.max(keep, 0), named.size());
        return new Plan(List.copyOf(named.subList(0, kept)), List.copyOf(named.subList(kept, named.size())),
                blocks.size());
    }

    /**
     * Counts the volumes named {@code base} in {@code world} or, with {@code confirm}, removes all but
     * {@code keep} of them. Runs on the world's thread, where {@code reply} and {@code audit} are called;
     * {@code audit} only hears about a removal.
     */
    public static void run(World world, String base, int keep, boolean confirm,
                           Consumer<String> reply, Consumer<String> audit) {
        world.execute(() -> {
            TriggerVolumeManager manager = world.getEntityStore().getStore()
                    .getResource(TriggerVolumesPlugin.get().getManagerResourceType());
            if (manager == null) {
                reply.accept("World " + world.getName() + " has no trigger volumes.");
                return;
            }
            Map<String, VolumeEntry> byId = new HashMap<>();
            List<Candidate> candidates = new ArrayList<>();
            for (VolumeEntry volume : manager.getVolumes()) {
                String name = VolumeNames.label(volume);
                if (!matches(base, name)) {
                    continue;
                }
                byId.put(volume.getId(), volume);
                candidates.add(new Candidate(volume.getId(), name, block(volume.getPosition())));
            }
            Plan plan = plan(base, candidates, keep);
            if (plan.total() == 0) {
                reply.accept("No trigger volume named " + base + " in " + world.getName() + ".");
                return;
            }
            reply.accept(volumes(plan.total()) + " named " + base + " in " + world.getName() + ", on "
                    + plan.positions() + (plan.positions() == 1 ? " block." : " blocks."));
            if (plan.removed().isEmpty()) {
                reply.accept("Nothing to remove: keeping " + plan.kept().size() + ".");
                return;
            }
            if (!confirm) {
                reply.accept("Add confirm to remove " + volumes(plan.removed().size()) + " and keep "
                        + plan.kept().size() + keptIds(plan) + ". keep 0 removes every one.");
                return;
            }
            for (Candidate candidate : plan.removed()) {
                VolumeEntry volume = byId.get(candidate.id());
                if (volume != null) {
                    volume.markPendingDestroy();
                }
            }
            String done = "removed " + volumes(plan.removed().size()) + " named " + base + " from "
                    + world.getName() + ", kept " + plan.kept().size() + keptIds(plan) + ".";
            reply.accept(Character.toUpperCase(done.charAt(0)) + done.substring(1)
                    + " They are gone at the end of this tick.");
            audit.accept(done);
        });
    }

    private static String block(Vector3d position) {
        return (long) Math.floor(position.x()) + " " + (long) Math.floor(position.y()) + " "
                + (long) Math.floor(position.z());
    }

    private static String volumes(int count) {
        return count + (count == 1 ? " trigger volume" : " trigger volumes");
    }

    private static String keptIds(Plan plan) {
        if (plan.kept().isEmpty() || plan.kept().size() > 3) {
            return "";
        }
        List<String> ids = new ArrayList<>();
        for (Candidate kept : plan.kept()) {
            ids.add(kept.id());
        }
        return " (" + String.join(", ", ids) + ")";
    }
}
