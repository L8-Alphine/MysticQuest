package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.service.PlayerSessionService;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.math.vector.Rotation3f;
import com.hypixel.hytale.server.core.modules.entity.teleport.Teleport;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.Universe;
import com.hypixel.hytale.server.core.universe.world.World;
import org.joml.Vector3d;

import javax.annotation.Nullable;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * §21.1 "Teleport to referenced quest location": moves a staff member to a trigger volume that
 * content names, such as a puzzle input. The volume is looked up on its own world's thread and the
 * teleport is applied on the staff member's, crossing worlds when needed.
 */
public final class QuestLocations {
    private QuestLocations() {
    }

    /**
     * @param volumeKey {@code world:volumeId} as content writes it; a bare id is looked up in the
     *         staff member's own world
     * @param reply receives one line saying what happened, on a world thread
     */
    public static void teleportToVolume(PlayerSessionService players, UUID staff, String volumeKey, Consumer<String> reply) {
        PlayerRef staffRef = players.playerRef(staff);
        if (staffRef == null) {
            reply.accept("You must be in a world to teleport.");
            return;
        }
        int colon = volumeKey.indexOf(':');
        String volumeId = colon < 0 ? volumeKey : volumeKey.substring(colon + 1);
        World world = colon < 0 ? worldById(staffRef.getWorldUuid()) : worldNamed(volumeKey.substring(0, colon));
        if (world == null) {
            reply.accept("No loaded world for " + volumeKey + ".");
            return;
        }
        world.execute(() -> {
            TriggerVolumeManager manager = world.getEntityStore().getStore()
                    .getResource(TriggerVolumesPlugin.get().getManagerResourceType());
            VolumeEntry volume = manager == null ? null : manager.getVolume(volumeId);
            if (volume == null) {
                reply.accept("No trigger volume " + volumeId + " in world " + world.getName() + ".");
                return;
            }
            Vector3d target = new Vector3d(volume.getPosition());
            players.runOnWorld(staff, (entity, store) -> {
                World here = store.getExternalData().getWorld();
                store.addComponent(entity, Teleport.getComponentType(),
                        Teleport.createForPlayer(here == world ? null : world, target, new Rotation3f()));
                reply.accept("Teleported to " + world.getName() + ":" + volumeId + ".");
            });
        });
    }

    @Nullable
    private static World worldNamed(String name) {
        for (World world : Universe.get().getWorlds().values()) {
            if (world.getName().equalsIgnoreCase(name)) {
                return world;
            }
        }
        return null;
    }

    @Nullable
    private static World worldById(@Nullable UUID worldUuid) {
        if (worldUuid == null) {
            return null;
        }
        for (World world : Universe.get().getWorlds().values()) {
            if (worldUuid.equals(world.getWorldConfig().getUuid())) {
                return world;
            }
        }
        return null;
    }
}
