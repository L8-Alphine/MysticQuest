package org.hyzionstudios.mysticquests;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.hyzionstudios.mysticquests.hytale.EntityIndexSystem;
import org.hyzionstudios.mysticquests.hytale.EntityVisibilitySystem;
import org.hyzionstudios.mysticquests.hytale.NameplateVisibilitySystem;
import org.hyzionstudios.mysticquests.hytale.StoryEntitySystems;
import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.hytale.TargetingPreventionSystem;
import org.hyzionstudios.mysticquests.integration.triggervolumes.MysticTriggerEditorI18n;
import org.hyzionstudios.mysticquests.integration.triggervolumes.MysticTriggerVolumeRegistrar;
import org.hyzionstudios.mysticquests.service.ConversationService;

import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class MysticquestsPlugin extends JavaPlugin {
    private MysticQuestsRuntime runtime;

    /**
     * Whether {@code setup()} reached the trigger volumes plugin.
     *
     * <p>False means it had not been set up yet — plugin order within a phase is not guaranteed —
     * and {@code start()} retries. A retry is late enough that a volume decoded in between still
     * loses its MysticQuests entries, so it also warns: that is a real, if narrow, data loss and it
     * should not pass silently.
     */
    private boolean triggerTypesRegistered;

    public MysticquestsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void setup() {
        ConversationService.registerInteractionPageSupplier(this);
        // Before anything can decode a trigger volume. Registering these in start() let volumes load
        // first, drop the effects and conditions they did not recognise, and persist that loss on the
        // next world write. The services these types call into are bound later, in runtime.start().
        triggerTypesRegistered = new MysticTriggerVolumeRegistrar(getLogger()).registerTypes();
        // Systems must be registered during setup, before the runtime exists, so they resolve their
        // services lazily and idle until start() has run.
        getEntityStoreRegistry().registerSystem(
                new EntityIndexSystem(
                        () -> runtime == null ? null : runtime.entityIndex(),
                        () -> runtime == null ? null : runtime.generationBridge(),
                        () -> runtime == null || runtime.narrative() == null
                                ? List.of()
                                : runtime.narrative().runtime().observedLayers()));
        // Story entity isolation (2.0, §8): damage, use and targeting between a story entity and
        // players outside its audience. Visibility rides the entity and nameplate systems.
        getEntityStoreRegistry().registerSystem(new StoryEntitySystems.DamageFilter(this::storyEntities));
        getEntityStoreRegistry().registerSystem(new StoryEntitySystems.UseFilter(this::storyEntities));
        getEntityStoreRegistry().registerSystem(new StoryEntitySystems.Targeting(this::storyEntities));
        getEntityStoreRegistry().registerSystem(
                new EntityVisibilitySystem(() -> runtime == null ? null : runtime.visibility()));
        // Entity visibility alone despawns the body but leaves the nameplate, which rides the
        // subject-side tracker map instead of the viewer-side visible set.
        getEntityStoreRegistry().registerSystem(
                new NameplateVisibilitySystem(() -> runtime == null ? null : runtime.visibility()));
        getEntityStoreRegistry().registerSystem(
                new TargetingPreventionSystem(() -> runtime == null ? null : runtime.targeting()));
    }

    private StoryEntityRegistry storyEntities() {
        MysticQuestsRuntime current = runtime;
        return current == null || current.narrative() == null ? null : current.narrative().runtime().storyEntities();
    }

    @Override
    protected void start() {
        if (!triggerTypesRegistered && !new MysticTriggerVolumeRegistrar(getLogger()).registerTypes()) {
            getLogger().at(Level.WARNING).log(
                    "Trigger volume types could not be registered; MysticQuests effects and "
                            + "conditions will be dropped from any volume that uses them. Install "
                            + "the trigger volumes plugin, or do not save worlds until it is.");
        }
        MysticTriggerEditorI18n.load(getLogger());
        runtime = new MysticQuestsRuntime(this);
        runtime.start();
        getLogger().at(Level.INFO).log("MysticQuests started!");
    }

    @Override
    protected void shutdown() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        getLogger().at(Level.INFO).log("MysticQuests shut down.");
    }
}
