package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.VisibilityService;

import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.SystemGroup;
import com.hypixel.hytale.component.dependency.Dependency;
import com.hypixel.hytale.component.dependency.Order;
import com.hypixel.hytale.component.dependency.SystemDependency;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collections;
import java.util.Iterator;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Hides non-player entities from individual viewers, the way the engine hides players.
 *
 * <p>The server ships {@code EntityTrackerSystems$HideFromPlayer}, which drops hidden entities out of
 * a viewer's visible set just after the tracker collects it and just before packets are built — so
 * the client receives a genuine despawn, and a genuine respawn when the entity is unhidden. That
 * system only processes refs whose archetype contains a {@link PlayerRef}, so NPCs, mobs, and props
 * are never hidden by it.
 *
 * <p>This runs in the same group with the same ordering and does the same thing for everything else.
 * The alternative — filtering {@code EntityUpdates} packets on the way out — cannot despawn an entity
 * the client has already spawned, so a hidden NPC would freeze in place instead of disappearing, and
 * every outgoing packet would pay for the inspection.
 *
 * <p>Cost when nothing is hidden is one atomic read per viewer per tick, via
 * {@link VisibilityService#isEmpty()}.
 */
public final class EntityVisibilitySystem extends EntityTickingSystem<EntityStore> {
    private final Supplier<VisibilityService> visibilitySupplier;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public EntityVisibilitySystem(Supplier<VisibilityService> visibilitySupplier) {
        this.visibilitySupplier = visibilitySupplier;
        this.query = Query.and(
                EntityTrackerSystems.EntityViewer.getComponentType(),
                PlayerRef.getComponentType());
        // Must run after the tracker has decided what is visible, and before the packets are queued.
        this.dependencies = Collections.singleton(
                new SystemDependency<>(Order.AFTER, EntityTrackerSystems.CollectVisible.class));
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.FIND_VISIBLE_ENTITIES_GROUP;
    }

    @Override
    public Set<Dependency<EntityStore>> getDependencies() {
        return dependencies;
    }

    @Override
    public Query<EntityStore> getQuery() {
        return query;
    }

    @Override
    public void tick(
            float delta,
            int index,
            ArchetypeChunk<EntityStore> chunk,
            Store<EntityStore> store,
            CommandBuffer<EntityStore> commandBuffer) {
        VisibilityService visibility = visibilitySupplier.get();
        if (visibility == null || visibility.isEmpty()) {
            return;
        }
        PlayerRef viewer = chunk.getComponent(index, PlayerRef.getComponentType());
        if (viewer == null) {
            return;
        }
        // HiddenPlayersManager is one shared set with no owner, so another mod releasing its own hide
        // can take ours with it. Re-asserting here bounds that to a single tick of visibility.
        visibility.enforcePlayerHides(viewer);
        // Presentation, not policy: a staff member in visibility bypass is shown everything.
        Set<UUID> hidden = visibility.presentedHiddenFrom(viewer.getUuid());
        if (hidden.isEmpty()) {
            return;
        }
        EntityTrackerSystems.EntityViewer entityViewer =
                chunk.getComponent(index, EntityTrackerSystems.EntityViewer.getComponentType());
        if (entityViewer == null || entityViewer.visible == null) {
            return;
        }

        Iterator<Ref<EntityStore>> visible = entityViewer.visible.iterator();
        while (visible.hasNext()) {
            Ref<EntityStore> candidate = visible.next();
            if (candidate == null || !candidate.isValid()) {
                continue;
            }
            // Players are already handled by the engine's own pass; skipping them here avoids
            // fighting it over the same entry.
            if (commandBuffer.getArchetype(candidate).contains(PlayerRef.getComponentType())) {
                continue;
            }
            UUIDComponent uuid = commandBuffer.getComponent(candidate, UUIDComponent.getComponentType());
            if (uuid != null && uuid.getUuid() != null && hidden.contains(uuid.getUuid())) {
                entityViewer.hiddenCount++;
                visible.remove();
            }
        }
    }
}
