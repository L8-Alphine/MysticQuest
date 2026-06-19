package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.integration.hyextras.MysticAddTagEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticEventEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticHasTagCondition;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticHyExtrasTriggerBridge;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticIncrementVariableEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticRemoveTagEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticRemoveVariableEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticSetVariableEffect;
import org.hyzionstudios.mysticquests.integration.hyextras.MysticVariableCondition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.logger.HytaleLogger;

import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

public final class HyExtrasBridge {
    private final boolean enabled;
    private final boolean exportPlayerState;
    private final HytaleLogger logger;

    public HyExtrasBridge(boolean enabled, boolean exportPlayerState, HytaleLogger logger) {
        this.enabled = enabled;
        this.exportPlayerState = exportPlayerState;
        this.logger = logger;
    }

    public boolean trigger(String action, UUID playerId) {
        if (!enabled || action == null || action.isBlank()) {
            return false;
        }
        // HyExtras is intentionally not a compile-time dependency. The bridge is the single
        // place to wire a stable HyExtras request API once that jar is available.
        return false;
    }

    public void registerMysticQuestTriggers(ScopedStateService scopedStateService, PlayerQuestService questService) {
        if (!enabled) {
            return;
        }
        MysticHyExtrasTriggerBridge.initialize(scopedStateService, questService, logger);
        registerEffect("mysticquests:add_tag", MysticAddTagEffect.class, MysticAddTagEffect.CODEC);
        registerEffect("mysticquests:remove_tag", MysticRemoveTagEffect.class, MysticRemoveTagEffect.CODEC);
        registerEffect("mysticquests:set_variable", MysticSetVariableEffect.class, MysticSetVariableEffect.CODEC);
        registerEffect("mysticquests:remove_variable", MysticRemoveVariableEffect.class, MysticRemoveVariableEffect.CODEC);
        registerEffect("mysticquests:increment_variable", MysticIncrementVariableEffect.class, MysticIncrementVariableEffect.CODEC);
        registerEffect("mysticquests:event", MysticEventEffect.class, MysticEventEffect.CODEC);
        registerCondition("mysticquests:has_tag", MysticHasTagCondition.class, MysticHasTagCondition.CODEC);
        registerCondition("mysticquests:variable", MysticVariableCondition.class, MysticVariableCondition.CODEC);
    }

    public void addTag(UUID playerId, String tag) {
        if (!canExport(playerId, tag)) {
            return;
        }
        invokeTagService("addTag", new Class<?>[] {UUID.class, String.class}, playerId, tag);
    }

    public void removeTag(UUID playerId, String tag) {
        if (!canExport(playerId, tag)) {
            return;
        }
        invokeTagService("removeTag", new Class<?>[] {UUID.class, String.class}, playerId, tag);
    }

    public void setVariable(UUID playerId, String key, Object value) {
        if (!canExport(playerId, key)) {
            return;
        }
        invokeVariableService("set", new Class<?>[] {UUID.class, String.class, Object.class}, playerId, key, value);
    }

    public void removeVariable(UUID playerId, String key) {
        if (!canExport(playerId, key)) {
            return;
        }
        invokeVariableService("remove", new Class<?>[] {UUID.class, String.class}, playerId, key);
    }

    private boolean canExport(UUID playerId, String key) {
        return enabled && exportPlayerState && playerId != null && key != null && !key.isBlank();
    }

    private void invokeTagService(String methodName, Class<?>[] parameterTypes, Object... args) {
        invokeService("getTagService", methodName, parameterTypes, args);
    }

    private void invokeVariableService(String methodName, Class<?>[] parameterTypes, Object... args) {
        invokeService("getVariableService", methodName, parameterTypes, args);
    }

    private void invokeService(String serviceGetter, String methodName, Class<?>[] parameterTypes, Object... args) {
        try {
            Class<?> pluginClass = Class.forName("org.hyzionstudios.hyextras.HyExtrasPlugin");
            Object plugin = pluginClass.getMethod("get").invoke(null);
            if (plugin == null) {
                return;
            }
            Object service = pluginClass.getMethod(serviceGetter).invoke(plugin);
            if (service == null) {
                return;
            }
            Method method = service.getClass().getMethod(methodName, parameterTypes);
            method.invoke(service, args);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("HyExtras player state export skipped.");
        }
    }

    private <T extends TriggerEffect> void registerEffect(String id, Class<T> type, BuilderCodec<T> codec) {
        invokeRegistration("registerEffect", id, type, codec);
    }

    private <T extends TriggerCondition> void registerCondition(String id, Class<T> type, BuilderCodec<T> codec) {
        invokeRegistration("registerCondition", id, type, codec);
    }

    private void invokeRegistration(String methodName, String id, Class<?> type, BuilderCodec<?> codec) {
        try {
            Class<?> adapter = Class.forName("org.hyzionstudios.hyextras.TriggerVolumeApiAdapter");
            Method method = adapter.getMethod(methodName, String.class, Class.class, BuilderCodec.class);
            method.invoke(null, id, type, codec);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("HyExtras MysticQuests trigger registration skipped for " + id + ".");
        }
    }
}
