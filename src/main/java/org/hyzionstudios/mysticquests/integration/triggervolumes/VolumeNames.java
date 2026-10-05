package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.manager.TriggerVolumeManager;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;

/**
 * How content names a trigger volume. The engine keys tool-placed and worldgen volumes by a
 * generated id (a UUID since 0.6.8) and shows builders the volume's {@link VolumeEntry#getName()
 * Name} instead, so the name is what content writes after {@code world:} and what every volume key
 * MysticQuests builds uses. Legacy {@code tv_*} ids were moved into the name by the engine, so
 * content that named them keeps working. A volume without a name falls back to its id.
 */
public final class VolumeNames {
    private VolumeNames() {
    }

    /** The volume's name, or its id when it has none. */
    public static String label(VolumeEntry volume) {
        String name = volume.getName();
        return name == null || name.isBlank() ? volume.getId() : name;
    }

    /**
     * The label of the volume the engine knows as {@code volumeId} in this store's world, or the id
     * itself when the volume is already gone, as on the EXIT its removal fires. World thread only.
     */
    public static String label(Store<EntityStore> store, String volumeId) {
        TriggerVolumeManager manager = store.getResource(TriggerVolumesPlugin.get().getManagerResourceType());
        VolumeEntry volume = manager == null ? null : manager.getVolume(volumeId);
        return volume == null ? volumeId : label(volume);
    }

    /** The volume content calls {@code nameOrId}: by engine id, else by name. World thread only. */
    @Nullable
    public static VolumeEntry find(TriggerVolumeManager manager, String nameOrId) {
        VolumeEntry byId = manager.getVolume(nameOrId);
        if (byId != null) {
            return byId;
        }
        for (VolumeEntry volume : manager.getVolumes()) {
            if (nameOrId.equals(volume.getName())) {
                return volume;
            }
        }
        return null;
    }
}
