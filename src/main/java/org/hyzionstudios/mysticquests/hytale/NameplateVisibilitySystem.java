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
import com.hypixel.hytale.server.core.entity.nameplate.NameplateSystems;
import com.hypixel.hytale.server.core.modules.entity.hitboxcollision.HitboxCollisionSystems;
import com.hypixel.hytale.server.core.modules.entity.tracker.EntityTrackerSystems;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.Collections;
import java.util.Iterator;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Stops nameplates from being sent to viewers the subject is hidden from.
 *
 * <p>Entity visibility has two independent sides, and hiding only one of them is what let a hidden
 * player keep a floating name:
 *
 * <ul>
 *   <li>The <b>viewer</b> side is {@code EntityViewer.visible}, a set of the entities one player can
 *       see. The engine's {@code HideFromPlayer} and our {@link EntityVisibilitySystem} filter this,
 *       which is what despawns the body.</li>
 *   <li>The <b>subject</b> side is {@code Visible.visibleTo}, a map from viewer to that viewer's
 *       tracker state, kept on the entity being looked at. {@code NameplateSystems$EntityTrackerUpdate}
 *       walks this map — not the viewer side — to decide who receives a {@code NameplateUpdate}.</li>
 * </ul>
 *
 * <p>Nothing in the engine reconciles the two within a tick, so a player removed from the viewer set
 * is still listed in every subject's {@code visibleTo} and keeps emitting nameplate packets. The body
 * disappears, the name does not. MysticNameTags writes the engine's {@code Nameplate} component, so
 * its plates travel this same path and show the same symptom.
 *
 * <p>This runs in {@code QUEUE_UPDATE_GROUP} immediately before the nameplate system and drops the
 * hidden viewers out of both maps. {@code ClearPreviouslyVisible} rotates and
 * {@code AddToVisible} repopulates them every tick, so the edit is per-tick and cannot accumulate:
 * unhiding restores the nameplate on the very next tick with no bookkeeping of our own.
 *
 * <p>The same filtering also suppresses model, skin, and effect updates aimed at a viewer who cannot
 * see the entity, which is correct for the same reason.
 *
 * <p>Cost when nothing is hidden is one atomic read per tracked entity per tick, via
 * {@link VisibilityService#isEmpty()}.
 */
public final class NameplateVisibilitySystem extends EntityTickingSystem<EntityStore> {
    private final Supplier<VisibilityService> visibilitySupplier;
    private final Query<EntityStore> query;
    private final Set<Dependency<EntityStore>> dependencies;

    public NameplateVisibilitySystem(Supplier<VisibilityService> visibilitySupplier) {
        this.visibilitySupplier = visibilitySupplier;
        this.query = Query.and(
                EntityTrackerSystems.Visible.getComponentType(),
                UUIDComponent.getComponentType());
        // The maps are populated before this group and read by the nameplate system inside it, so the
        // only ordering that matters is landing in between.
        // Hitbox data travels the same subject-side map. Filtering before it as well means a viewer an
        // overlay barrier is hidden from is never sent its collision (2.0, §9).
        this.dependencies = Set.of(
                new SystemDependency<>(Order.BEFORE, NameplateSystems.EntityTrackerUpdate.class),
                new SystemDependency<>(Order.BEFORE, HitboxCollisionSystems.EntityTrackerUpdate.class));
    }

    @Override
    public SystemGroup<EntityStore> getGroup() {
        return EntityTrackerSystems.QUEUE_UPDATE_GROUP;
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
        UUIDComponent uuid = chunk.getComponent(index, UUIDComponent.getComponentType());
        if (uuid == null || uuid.getUuid() == null) {
            return;
        }
        EntityTrackerSystems.Visible visible =
                chunk.getComponent(index, EntityTrackerSystems.Visible.getComponentType());
        if (visible == null) {
            return;
        }
        UUID subject = uuid.getUuid();
        dropHiddenViewers(visible.visibleTo, subject, visibility, commandBuffer);
        dropHiddenViewers(visible.newlyVisibleTo, subject, visibility, commandBuffer);
    }

    /** Removes every viewer that must not see {@code subject} from one of the tracker maps. */
    private static void dropHiddenViewers(
            Map<Ref<EntityStore>, EntityTrackerSystems.EntityViewer> viewers,
            UUID subject,
            VisibilityService visibility,
            CommandBuffer<EntityStore> commandBuffer) {
        if (viewers == null || viewers.isEmpty()) {
            return;
        }
        Iterator<Map.Entry<Ref<EntityStore>, EntityTrackerSystems.EntityViewer>> entries =
                viewers.entrySet().iterator();
        while (entries.hasNext()) {
            Ref<EntityStore> viewerRef = entries.next().getKey();
            if (viewerRef == null || !viewerRef.isValid()) {
                continue;
            }
            PlayerRef viewer = commandBuffer.getComponent(viewerRef, PlayerRef.getComponentType());
            if (viewer != null && visibility.isPresentedHidden(viewer.getUuid(), subject)) {
                entries.remove();
            }
        }
    }
}
