package org.hyzionstudios.mysticquests.hytale;

import org.hyzionstudios.mysticquests.service.TargetingPreventionService;

import com.hypixel.hytale.builtin.npccombatactionevaluator.memory.TargetMemory;
import com.hypixel.hytale.component.Archetype;
import com.hypixel.hytale.component.ArchetypeChunk;
import com.hypixel.hytale.component.CommandBuffer;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.component.system.tick.EntityTickingSystem;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import it.unimi.dsi.fastutil.ints.IntSet;

import java.util.function.Supplier;

/**
 * Clears MysticQuests-protected players out of every NPC's combat target memory each tick.
 *
 * <p>Runs against every entity that has a {@link TargetMemory}, so the early exits matter: a server
 * with nobody protected pays one boolean check per NPC per tick, and resolving protected players to
 * entity indexes happens once per store per tick rather than once per NPC.
 */
public final class TargetingPreventionSystem extends EntityTickingSystem<EntityStore> {
    private final Supplier<TargetingPreventionService> serviceSupplier;
    private final Query<EntityStore> query = Archetype.of(TargetMemory.getComponentType());

    public TargetingPreventionSystem(Supplier<TargetingPreventionService> serviceSupplier) {
        this.serviceSupplier = serviceSupplier;
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
        TargetingPreventionService service = serviceSupplier.get();
        if (service == null || !service.hasProtectedPlayers()) {
            return;
        }
        TargetMemory memory = chunk.getComponent(index, TargetMemory.getComponentType());
        if (memory == null) {
            return;
        }
        IntSet protectedIndexes = service.protectedEntityIndexes(store);
        if (protectedIndexes.isEmpty()) {
            return;
        }
        service.clearProtectedTargets(memory, protectedIndexes);
    }
}
