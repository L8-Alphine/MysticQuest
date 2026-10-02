package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;

import com.hypixel.hytale.builtin.triggervolumes.TriggerVolumesPlugin;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.logger.HytaleLogger;

import java.util.logging.Level;

/**
 * Registers MysticQuests effect and condition types with Hytale's trigger volume system, so world
 * builders can drive quest state from volumes placed in the editor.
 *
 * <p>These registrations used to go through HyExtras by reflection, which meant volumes only worked
 * when that mod happened to be installed — even though MysticQuests compiles against the trigger
 * volume API directly and never needed the indirection. Calling
 * {@link TriggerVolumesPlugin} here makes the feature stand on its own.
 *
 * <p>Every call is wrapped: a server without the trigger volumes plugin, or a version whose
 * registration API has moved, logs once and leaves the rest of the mod running rather than failing
 * startup over an optional integration.
 *
 * <h2>Registration is split in two, and the order matters</h2>
 *
 * <p>{@link #registerTypes()} must run in the plugin's {@code setup()}, before anything decodes a
 * trigger volume. Both halves used to run together in {@code start()}, and the consequence was not
 * that volumes stopped working — it was that they lost data. A volume decoded before its effect type
 * is registered drops the unrecognised entry, and the next time that world is written the loss is
 * persisted. Authors saw MysticQuests conditions and effects silently disappear from volumes they
 * had already placed.
 *
 * <p>{@link #bindServices} stays in {@code start()}, because the services it binds do not exist any
 * earlier. That is safe: {@link MysticTriggerBridge} is static with null guards throughout, so a
 * volume that fires between the two calls declines to act rather than throwing.
 */
public final class MysticTriggerVolumeRegistrar {

    /**
     * Whether every type is registered with the platform.
     *
     * <p>Static because the two lifecycle phases each build their own registrar, and "already
     * registered" is a property of the platform rather than of either instance. Registering a type
     * twice is not obviously harmless, so this is what makes the {@code start()} retry safe.
     */
    private static boolean registered;

    private static int failures;

    private final HytaleLogger logger;

    public MysticTriggerVolumeRegistrar(HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Binds the services the registered types call into at fire time.
     *
     * <p>Call from {@code start()}, after {@link #registerTypes()} has run in {@code setup()}.
     */
    public void bindServices(ScopedStateService scopedStateService, PlayerQuestService questService) {
        MysticTriggerBridge.initialize(scopedStateService, questService, logger);
    }

    /**
     * Registers every MysticQuests volume type. Safe to call when trigger volumes are unavailable.
     *
     * <p>Call from {@code setup()}. See the class note on why this cannot wait for {@code start()}.
     *
     * <p>Idempotent, and it reports whether it got through. The manifest's
     * {@code Hytale:TriggerVolumes} dependency should guarantee that plugin is set up first, so a
     * {@code false} here means something is wrong rather than merely early — but a caller that sees
     * it should still call again in {@code start()}, because registering late beats not registering
     * at all. Calling twice is harmless; the second call short-circuits.
     *
     * @return true when every type registered, false when the platform was not reachable
     */
    public boolean registerTypes() {
        if (registered) {
            return true;
        }
        failures = 0;
        // The generic action effect is the important one: it reaches every event type, including
        // those registered by other mods. The rest are kept because existing worlds reference them.
        registerEffect("mysticquests:action", MysticActionEffect.class, MysticActionEffect.CODEC);
        registerEffect("mysticquests:add_tag", MysticAddTagEffect.class, MysticAddTagEffect.CODEC);
        registerEffect("mysticquests:remove_tag", MysticRemoveTagEffect.class, MysticRemoveTagEffect.CODEC);
        registerEffect("mysticquests:set_variable", MysticSetVariableEffect.class, MysticSetVariableEffect.CODEC);
        registerEffect("mysticquests:remove_variable", MysticRemoveVariableEffect.class, MysticRemoveVariableEffect.CODEC);
        registerEffect("mysticquests:increment_variable", MysticIncrementVariableEffect.class, MysticIncrementVariableEffect.CODEC);
        registerEffect("mysticquests:event", MysticEventEffect.class, MysticEventEffect.CODEC);
        registerEffect("mysticquests:rich_message", MysticRichMessageEffect.class, MysticRichMessageEffect.CODEC);
        registerEffect("mysticquests:run_command", MysticRunCommandEffect.class, MysticRunCommandEffect.CODEC);

        // Narrative runtime types: per-audience activation and puzzle inputs. See NarrativeTriggerBridge.
        registerEffect("mysticquests:puzzle_input", MysticPuzzleInputEffect.class, MysticPuzzleInputEffect.CODEC);
        registerEffect("mysticquests:puzzle_reset", MysticPuzzleResetEffect.class, MysticPuzzleResetEffect.CODEC);
        registerEffect("mysticquests:cutscene_play", MysticCutscenePlayEffect.class, MysticCutscenePlayEffect.CODEC);
        registerEffect("mysticquests:trigger_state", MysticTriggerStateEffect.class, MysticTriggerStateEffect.CODEC);

        registerCondition("mysticquests:has_tag", MysticHasTagCondition.class, MysticHasTagCondition.CODEC);
        registerCondition("mysticquests:variable", MysticVariableCondition.class, MysticVariableCondition.CODEC);
        registerCondition("mysticquests:trigger_enabled", MysticTriggerEnabledCondition.class, MysticTriggerEnabledCondition.CODEC);
        registerCondition("mysticquests:puzzle_input_available",
                MysticPuzzleInputAvailableCondition.class, MysticPuzzleInputAvailableCondition.CODEC);

        registered = failures == 0;
        return registered;
    }

    private <T extends TriggerEffect> void registerEffect(String id, Class<T> type, BuilderCodec<T> codec) {
        try {
            TriggerVolumesPlugin.get().registerEffectType(id, type, codec);
        } catch (RuntimeException | LinkageError failure) {
            failures++;
            logger.at(Level.FINE).withCause(failure)
                    .log("MysticQuests could not register trigger volume effect '" + id + "' yet.");
        }
    }

    private <T extends TriggerCondition> void registerCondition(String id, Class<T> type, BuilderCodec<T> codec) {
        try {
            TriggerVolumesPlugin.get().registerConditionType(id, type, codec);
        } catch (RuntimeException | LinkageError failure) {
            failures++;
            logger.at(Level.FINE).withCause(failure)
                    .log("MysticQuests could not register trigger volume condition '" + id + "' yet.");
        }
    }
}
