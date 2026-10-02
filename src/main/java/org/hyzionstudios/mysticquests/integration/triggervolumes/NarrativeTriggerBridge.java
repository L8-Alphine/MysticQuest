package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService.InputStatus;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerEvent;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.manager.VolumeEntry;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Connects the narrative trigger-volume types to the {@link NarrativeRuntime}.
 *
 * <p>Static for the same reason as {@link MysticTriggerBridge}: the engine instantiates effect and
 * condition objects from volume JSON, so they cannot be constructor-injected. The runtime is bound
 * in {@code start()} and unbound on shutdown. Until then every type declines to act: conditions
 * pass, so a volume behaves as if MysticQuests were absent, and effects do nothing.
 */
public final class NarrativeTriggerBridge {
    private static volatile NarrativeRuntime runtime;
    private static volatile HytaleLogger logger;

    private NarrativeTriggerBridge() {
    }

    public static void bind(@Nullable NarrativeRuntime narrative, @Nullable HytaleLogger log) {
        runtime = narrative;
        logger = log;
    }

    /** The {@code mysticquests:trigger_enabled} gate: is this volume logically enabled for the actor? */
    static boolean enabled(TriggerContext context, String volumeOverride) {
        NarrativeRuntime narrative = runtime;
        if (narrative == null) {
            return true;
        }
        String volumeKey = volumeOverride == null || volumeOverride.isBlank() ? volumeKey(context) : volumeOverride.trim();
        return narrative.activation().isEnabled(volumeKey, player(context));
    }

    /** True when the input still matters to the actor's audience: selected, and not yet activated. */
    static boolean inputAvailable(TriggerContext context, String puzzle, String input) {
        NarrativeRuntime narrative = runtime;
        UUID player = player(context);
        Optional<NamespacedId> puzzleId = NamespacedId.tryParse(puzzle);
        if (narrative == null || player == null || puzzleId.isEmpty()) {
            return false;
        }
        return narrative.puzzles().inputStatus(player, puzzleId.get(), input) == InputStatus.AVAILABLE;
    }

    static void puzzleInput(TriggerContext context, String puzzle, String input, boolean release) {
        NarrativeRuntime narrative = runtime;
        UUID player = player(context);
        Optional<NamespacedId> puzzleId = NamespacedId.tryParse(puzzle);
        if (narrative == null || player == null) {
            return;
        }
        if (puzzleId.isEmpty()) {
            warn("Volume " + volumeKey(context) + " names an invalid puzzle id '" + puzzle + "'.");
            return;
        }
        if (!narrative.activation().isEnabled(volumeKey(context), player)) {
            return;
        }
        var result = narrative.puzzles().input(player, world(context), puzzleId.get(), input, !release);
        switch (result.outcome()) {
            case UNKNOWN_PUZZLE, UNKNOWN_INPUT -> warn("Volume " + volumeKey(context) + ": " + result.message());
            default -> {
            }
        }
    }

    static void cutscenePlay(TriggerContext context, String cutscene) {
        NarrativeRuntime narrative = runtime;
        UUID player = player(context);
        Optional<NamespacedId> id = NamespacedId.tryParse(cutscene);
        if (narrative == null || player == null || id.isEmpty()) {
            return;
        }
        switch (narrative.cutscenes().play(player, id.get(), null)) {
            case UNKNOWN_CUTSCENE -> warn("Volume " + volumeKey(context) + ": no cutscene " + cutscene);
            case PENDING -> warn("Volume " + volumeKey(context) + ": cutscene " + cutscene + " waits for the previous scene to finish");
            default -> {
            }
        }
    }

    static void puzzleReset(TriggerContext context, String puzzle, boolean reroll) {
        NarrativeRuntime narrative = runtime;
        UUID player = player(context);
        Optional<NamespacedId> puzzleId = NamespacedId.tryParse(puzzle);
        if (narrative == null || player == null || puzzleId.isEmpty()) {
            return;
        }
        narrative.puzzles().reset(player, puzzleId.get(), reroll, "volume:" + volumeKey(context));
    }

    /**
     * Sets a logical override for the triggering player's audience. A session-scoped change applies
     * to every active session the player takes part in, because a volume does not know which story
     * it belongs to; content that needs one story only should use the action from that story's
     * outputs instead.
     */
    static void triggerState(TriggerContext context, String volume, TriggerScope scope, @Nullable Boolean enabled) {
        NarrativeRuntime narrative = runtime;
        UUID player = player(context);
        if (narrative == null || (player == null && scope != TriggerScope.GLOBAL)) {
            return;
        }
        String volumeKey = volume == null || volume.isBlank() ? volumeKey(context) : volume.trim();
        ScopeContext base = player == null
                ? ScopeContext.none()
                : ScopeContext.player(player).withParty(narrative.audiences().party(player).orElse(null));
        if (scope == TriggerScope.STORY_SESSION && player != null) {
            for (var owner : narrative.audiences().owners(player)) {
                for (var session : narrative.sessions().load(owner)) {
                    if (session.active()) {
                        report(narrative.activation().set(scope, volumeKey, enabled, base.withSession(session.id(), session.storyKey())), context);
                    }
                }
            }
            return;
        }
        report(narrative.activation().set(scope, volumeKey, enabled, base), context);
    }

    private static void report(StateResult result, TriggerContext context) {
        if (result.rejected()) {
            warn("Volume " + volumeKey(context) + " could not change trigger state: " + result.message());
        }
    }

    /** The triggering player's UUID, or null when the actor is not a player. */
    @Nullable
    static UUID player(TriggerContext context) {
        Ref<EntityStore> ref = context.getEntityRef();
        if (ref == null || !ref.isValid()) {
            return null;
        }
        PlayerRef playerRef = context.getStore().getComponent(ref, PlayerRef.getComponentType());
        return playerRef == null ? null : playerRef.getUuid();
    }

    static String volumeKey(TriggerContext context) {
        VolumeEntry volume = context.getVolume();
        return TriggerEvent.volumeKey(volume.getWorldName(), volume.getId());
    }

    @Nullable
    private static String world(TriggerContext context) {
        String world = context.getVolume().getWorldName();
        return world == null || world.isBlank() ? null : world;
    }

    private static void warn(String message) {
        HytaleLogger log = logger;
        if (log != null) {
            log.at(Level.WARNING).log(message);
        }
    }
}
