package org.hyzionstudios.mysticquests.integration;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.UUID;
import java.util.logging.Level;

/**
 * One-way mirror of player tags and variables into HyExtras, for servers running both mods.
 *
 * <p>MysticQuests owns its own tag and variable system and does not need HyExtras for anything. This
 * exists only so a server that also runs HyExtras sees one set of player tags rather than two
 * disconnected ones: MysticQuests is the source of truth, and every player-scope change is pushed
 * across. Nothing is ever read back.
 *
 * <p>Trigger volume types are no longer registered through here — MysticQuests compiles against the
 * trigger volume API directly, so {@code MysticTriggerVolumeRegistrar} does it without HyExtras
 * needing to be present at all.
 *
 * <p>Access stays reflective because HyExtras is not a compile-time dependency. When HyExtras is
 * absent the bridge disables itself after the first miss, rather than paying for a failed class
 * lookup on every single tag change.
 */
public final class HyExtrasBridge {
    private final boolean enabled;
    private final boolean exportPlayerState;
    private final HytaleLogger logger;

    /** Cleared after the first failed lookup, so a server without HyExtras stops trying. */
    private volatile boolean available = true;

    public HyExtrasBridge(boolean enabled, boolean exportPlayerState, HytaleLogger logger) {
        this.enabled = enabled;
        this.exportPlayerState = exportPlayerState;
        this.logger = logger;
    }

    /**
     * Legacy hook behind the {@code triggerHyExtrasEffect} event type.
     *
     * @return false always — HyExtras exposes no stable request API to call into
     */
    public boolean trigger(String action, UUID playerId) {
        return false;
    }

    public void addTag(UUID playerId, String tag) {
        if (canExport(playerId, tag)) {
            invoke("getTagService", "addTag", new Class<?>[] {UUID.class, String.class}, playerId, tag);
        }
    }

    public void removeTag(UUID playerId, String tag) {
        if (canExport(playerId, tag)) {
            invoke("getTagService", "removeTag", new Class<?>[] {UUID.class, String.class}, playerId, tag);
        }
    }

    public void setVariable(UUID playerId, String key, Object value) {
        if (canExport(playerId, key)) {
            invoke("getVariableService", "set",
                    new Class<?>[] {UUID.class, String.class, Object.class}, playerId, key, value);
        }
    }

    public void removeVariable(UUID playerId, String key) {
        if (canExport(playerId, key)) {
            invoke("getVariableService", "remove", new Class<?>[] {UUID.class, String.class}, playerId, key);
        }
    }

    private boolean canExport(UUID playerId, String key) {
        return enabled && exportPlayerState && available && playerId != null && key != null && !key.isBlank();
    }

    private void invoke(String serviceGetter, String methodName, Class<?>[] parameterTypes, Object... args) {
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
            service.getClass().getMethod(methodName, parameterTypes).invoke(service, args);
        } catch (ClassNotFoundException | NoSuchMethodException missing) {
            // HyExtras is absent, or has a different shape. Stop retrying: this path runs on every
            // player tag change, and a failing Class.forName is not cheap.
            available = false;
            logger.at(Level.FINE).withCause(missing)
                    .log("HyExtras is not available; MysticQuests will stop mirroring player state to it.");
        } catch (ReflectiveOperationException | RuntimeException failure) {
            logger.at(Level.FINE).withCause(failure).log("HyExtras player state mirror failed.");
        }
    }
}
