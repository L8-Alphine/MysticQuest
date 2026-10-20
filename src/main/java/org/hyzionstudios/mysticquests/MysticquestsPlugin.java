package org.hyzionstudios.mysticquests;

import com.hypixel.hytale.builtin.adventure.worldevents.condition.location.EventLocation;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;
import org.hyzionstudios.mysticquests.hytale.EncounterSystems;
import org.hyzionstudios.mysticquests.hytale.EntityIndexSystem;
import org.hyzionstudios.mysticquests.hytale.EntityVisibilitySystem;
import org.hyzionstudios.mysticquests.hytale.ExternalQuestSyncSystem;
import org.hyzionstudios.mysticquests.hytale.GameFlagObjectiveSystem;
import org.hyzionstudios.mysticquests.hytale.GatherObjectiveSystem;
import org.hyzionstudios.mysticquests.hytale.InteractObjectiveSystems;
import org.hyzionstudios.mysticquests.hytale.KillObjectiveSystem;
import org.hyzionstudios.mysticquests.hytale.StoryEntityDeathSystem;
import org.hyzionstudios.mysticquests.hytale.NameplateVisibilitySystem;
import org.hyzionstudios.mysticquests.hytale.StoryEntitySystems;
import org.hyzionstudios.mysticquests.narrative.entity.StoryEntityRegistry;
import org.hyzionstudios.mysticquests.hytale.TargetingPreventionSystem;
import org.hyzionstudios.mysticquests.hytale.WorldEventSystems;
import org.hyzionstudios.mysticquests.integration.worldevents.QuestPlayerLocation;
import org.hyzionstudios.mysticquests.integration.worldevents.WorldEventTracker;
import org.hyzionstudios.mysticquests.integration.triggervolumes.MysticTriggerEditorI18n;
import org.hyzionstudios.mysticquests.integration.triggervolumes.MysticTriggerVolumeRegistrar;
import org.hyzionstudios.mysticquests.service.ConversationService;

import java.util.List;
import java.util.logging.Level;
import javax.annotation.Nonnull;

public class MysticquestsPlugin extends JavaPlugin {
    private MysticQuestsRuntime runtime;

    /**
     * What the world-event systems have seen. Those systems run from {@code setup()} on, before the
     * runtime exists, so the plugin owns it and hands it to the runtime.
     */
    private final WorldEventTracker worldEvents = new WorldEventTracker();

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
        // Feeds kill objectives from the engine's death systems, the hook MysticRPG's xp award uses.
        getEntityStoreRegistry().registerSystem(new KillObjectiveSystem(
                () -> runtime == null ? null : runtime.signalBus(),
                () -> runtime == null ? null : runtime.generationBridge()));
        // A claimed story NPC's death runs its onDeath actions for the audience that owns it (§8).
        getEntityStoreRegistry().registerSystem(new StoryEntityDeathSystem(
                () -> runtime == null || runtime.narrative() == null ? null : runtime.narrative().runtime()));
        // Interaction objectives come from the engine's use events; PlayerInteractEvent is never raised.
        getEntityStoreRegistry().registerSystem(new InteractObjectiveSystems.UseBlock(
                () -> runtime == null ? null : runtime.signalBus()));
        getEntityStoreRegistry().registerSystem(new InteractObjectiveSystems.UseEntity(
                () -> runtime == null ? null : runtime.signalBus(),
                () -> runtime == null ? null : runtime.generationBridge()));
        // Gather objectives count held items on every inventory change, as Hytale's own gather tasks do.
        getEntityStoreRegistry().registerSystem(new GatherObjectiveSystem(() -> runtime == null ? null : runtime.questService()));
        // Reads quests other mods run (Endgame & QoL, ZiggfreedCommon) on each player's world thread.
        getEntityStoreRegistry().registerSystem(new ExternalQuestSyncSystem(() -> runtime == null ? null : runtime.externalQuests()));
        registerStorySystems();
    }

    /**
     * Hooks into Hytale's own story: the world events behind the goblin breaches, the encounters behind
     * boss fights, and the universe's game flags. Each is a built-in plugin a server can turn off, so a
     * missing one costs only its objectives, never the rest of MysticQuests.
     */
    private void registerStorySystems() {
        try {
            // Before assets load: MysticQuests' own breach event places itself with this type.
            getCodecRegistry(EventLocation.Config.TYPE_CODEC)
                    .register(QuestPlayerLocation.ID, QuestPlayerLocation.Config.class, QuestPlayerLocation.Config.CODEC);
            getEntityStoreRegistry().registerSystem(new WorldEventSystems.Track(() -> worldEvents));
            getEntityStoreRegistry().registerSystem(new WorldEventSystems.Observe(
                    () -> worldEvents, () -> runtime == null ? null : runtime.signalBus()));
            getEntityStoreRegistry().registerSystem(new WorldEventSystems.End(
                    () -> worldEvents, () -> runtime == null ? null : runtime.signalBus()));
            getEntityStoreRegistry().registerSystem(new WorldEventSystems.Signal(
                    () -> runtime == null ? null : runtime.signalBus()));
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().at(Level.WARNING).withCause(unavailable).log(
                    "Hytale's world events are unavailable; worldEvent and worldEventSignal objectives will not count.");
        }
        try {
            getEntityStoreRegistry().registerSystem(new EncounterSystems.Membership(
                    () -> runtime == null ? null : runtime.signalBus()));
            getEntityStoreRegistry().registerSystem(new EncounterSystems.BossDeath(
                    () -> runtime == null ? null : runtime.signalBus(),
                    () -> runtime == null ? null : runtime.watchedBosses()));
        } catch (RuntimeException | LinkageError unavailable) {
            getLogger().at(Level.WARNING).withCause(unavailable).log(
                    "Hytale's encounters are unavailable; encounter and bossDefeated objectives will not count.");
        }
        getEntityStoreRegistry().registerSystem(new GameFlagObjectiveSystem(
                () -> runtime == null ? null : runtime.questService(),
                () -> runtime == null ? null : runtime.storyWorld()));
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
        runtime = new MysticQuestsRuntime(this, worldEvents);
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
