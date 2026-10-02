package org.hyzionstudios.mysticquests.integration;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.lang.reflect.Method;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Read-only view of MysticVanish's opinion on who may see whom.
 *
 * <p>MysticVanish hides players through the same per-viewer {@code HiddenPlayersManager} set that
 * MysticQuests uses. That set is shared engine state with no notion of an owner, so a naive
 * {@code showPlayer} from one mod silently cancels the other's hide — which is how a vanished admin
 * became visible again the moment any quest released a hide.
 *
 * <p>{@link org.hyzionstudios.mysticquests.service.VisibilityService} consults this before it ever
 * lifts an engine-level hide. When MysticVanish is absent, unreachable, or throwing, every query
 * answers "no opinion" and MysticQuests behaves exactly as it did before — the integration can only
 * ever prevent an unhide, never cause one.
 *
 * <p>Bound reflectively rather than at compile time, matching {@link HyCitizensBridge} and
 * {@link HyExtrasBridge}: MysticVanish is an optional sibling mod, not a build dependency.
 */
public final class MysticVanishBridge {
    private static final String PROVIDER_CLASS = "org.hyzionstudios.mysticvanish.api.MysticVanishProvider";

    private final HytaleLogger logger;

    @Nullable
    private final Method isRegistered;
    @Nullable
    private final Method get;
    @Nullable
    private final Method canSee;
    @Nullable
    private final Method isVanished;

    /** Set once the first reflective call fails, so a broken API version is reported only once. */
    private volatile boolean degraded;

    public MysticVanishBridge(boolean enabled, HytaleLogger logger) {
        this.logger = logger;
        Method isRegisteredMethod = null;
        Method getMethod = null;
        Method canSeeMethod = null;
        Method isVanishedMethod = null;
        if (enabled) {
            try {
                Class<?> provider = Class.forName(PROVIDER_CLASS);
                isRegisteredMethod = provider.getMethod("isRegistered");
                getMethod = provider.getMethod("get");
                Class<?> api = Class.forName("org.hyzionstudios.mysticvanish.api.MysticVanishAPI");
                canSeeMethod = api.getMethod("canSee", UUID.class, UUID.class);
                isVanishedMethod = api.getMethod("isVanished", UUID.class);
            } catch (ClassNotFoundException notInstalled) {
                // The common case: MysticVanish simply is not on this server.
                isRegisteredMethod = null;
            } catch (ReflectiveOperationException | RuntimeException mismatch) {
                logger.at(Level.WARNING).withCause(mismatch).log(
                        "MysticVanish is present but its API does not match what MysticQuests expects;"
                                + " quest visibility will not defer to vanish state.");
                isRegisteredMethod = null;
            }
        }
        this.isRegistered = isRegisteredMethod;
        this.get = getMethod;
        this.canSee = canSeeMethod;
        this.isVanished = isVanishedMethod;
    }

    /** True when MysticVanish is installed and has published its API. */
    public boolean isAvailable() {
        if (isRegistered == null || degraded) {
            return false;
        }
        try {
            return Boolean.TRUE.equals(isRegistered.invoke(null));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            degrade(failure);
            return false;
        }
    }

    /**
     * True when MysticVanish wants {@code target} to stay hidden from {@code viewer}.
     *
     * <p>Answers false whenever MysticVanish is unavailable or cannot answer, so an absent or broken
     * vanish plugin never leaves a player stuck invisible.
     */
    public boolean wantsHidden(UUID viewer, UUID target) {
        if (viewer == null || target == null || !isAvailable()) {
            return false;
        }
        try {
            Object api = get.invoke(null);
            if (api == null) {
                return false;
            }
            // canSee is the authoritative pairwise answer: it accounts for vanish level against the
            // viewer's see level, which isVanished alone cannot express.
            return Boolean.FALSE.equals(canSee.invoke(api, viewer, target));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            degrade(failure);
            return false;
        }
    }

    /** True when the player is vanished at all, regardless of who is looking. */
    public boolean isVanished(UUID player) {
        if (player == null || !isAvailable()) {
            return false;
        }
        try {
            Object api = get.invoke(null);
            return api != null && Boolean.TRUE.equals(isVanished.invoke(api, player));
        } catch (ReflectiveOperationException | RuntimeException failure) {
            degrade(failure);
            return false;
        }
    }

    /**
     * Stops querying MysticVanish after a failure. Visibility is reconciled every tick, so retrying a
     * broken call forever would repeat the same failure at tick rate.
     */
    private void degrade(Throwable failure) {
        if (degraded) {
            return;
        }
        degraded = true;
        logger.at(Level.WARNING).withCause(failure).log(
                "MysticVanish API call failed; MysticQuests will stop deferring to vanish state for the"
                        + " rest of this session.");
    }
}
