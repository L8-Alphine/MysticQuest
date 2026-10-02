package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.narrative.PresentationLayer;
import org.hyzionstudios.mysticquests.state.EntityIndexService;

import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.entity.UUIDComponent;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * Feeds {@link EntityIndexService} the position of every UUID-backed non-player entity each tick,
 * so quest content can target "the nearest NPC" without scanning the world itself.
 *
 * <p>Registered during plugin setup, which runs before the runtime exists, so the service is
 * resolved through a supplier and the system idles until the runtime has started.
 */
public final class EntityIndexSystem extends EntityTickingSystem<EntityStore> {
    private final Supplier<EntityIndexService> indexSupplier;
    private final Supplier<MysticGenerationBridge> generationSupplier;
    private final Supplier<List<PresentationLayer>> layersSupplier;
    private final Query<EntityStore> query = Archetype.of(
            UUIDComponent.getComponentType(),
            TransformComponent.getComponentType());

    public EntityIndexSystem(Supplier<EntityIndexService> indexSupplier) {
        this(indexSupplier, () -> null, List::of);
    }

    public EntityIndexSystem(
            Supplier<EntityIndexService> indexSupplier,
            Supplier<MysticGenerationBridge> generationSupplier,
            Supplier<List<PresentationLayer>> layersSupplier) {
        this.indexSupplier = indexSupplier;
        this.generationSupplier = generationSupplier;
        this.layersSupplier = layersSupplier;
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
        EntityIndexService entityIndex = indexSupplier.get();
        if (entityIndex == null) {
            return;
        }
        Ref<EntityStore> ref = chunk.getReferenceTo(index);
        if (ref == null || !ref.isValid()) {
            return;
        }
        // Players are addressed by their own UUID everywhere else and are never "the nearest NPC".
        if (store.getComponent(ref, PlayerRef.getComponentType()) != null) {
            return;
        }
        // Generated NPCs are indexed by their MysticGeneration identity as well, which is a
        // different key from the entity UUID above and the only one that survives a republish.
        MysticGenerationBridge generation = generationSupplier.get();
        if (generation != null) {
            generation.index(store, ref, chunk, index);
        }
        UUIDComponent uuid = chunk.getComponent(index, UUIDComponent.getComponentType());
        TransformComponent transform = chunk.getComponent(index, TransformComponent.getComponentType());
        if (uuid == null || uuid.getUuid() == null || transform == null || transform.getPosition() == null) {
            return;
        }
        entityIndex.index(store, ref, uuid.getUuid(), transform.getPosition());
        // Story NPCs and overlay entities named by MysticGeneration identity are re-bound to whichever
        // entity carries that identity now, so a republish does not free them into the shared world.
        if (generation == null) {
            return;
        }
        boolean wanted = false;
        List<PresentationLayer> layers = layersSupplier.get();
        for (PresentationLayer layer : layers) {
            wanted |= layer.wantsObservation();
        }
        if (wanted) {
            UUID entity = uuid.getUuid();
            generation.identify(chunk, index).ifPresent(npc -> layers.forEach(layer -> layer.observe(entity, npc.uuid())));
        }
    }
}
