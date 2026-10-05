package org.hyzionstudios.mysticquests.integration.mysticidentity;

import com.hypixel.hytale.logger.HytaleLogger;

import java.util.Optional;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.logging.Level;

/**
 * Puts MysticQuests on the MysticIdentity player portal (2.0 specification §19.1): active quests
 * with their current step and objectives, quest history, and story milestones, read-only.
 *
 * <p>MysticIdentity is optional and compile-only. Its classes come from the MysticIdentity mod
 * through Hytale's cross-plugin classloading, so everything naming them lives in
 * {@link PortalRegistration} and {@link QuestsPortalProvider}, loaded only after the probe here
 * succeeds. MysticIdentity publishes its API during its own start-up, whose order against ours is
 * not guaranteed, so registration is retried every few seconds until it holds, and again if
 * MysticIdentity restarts. This is the pattern MysticEconomy, MysticGuilds and MysticRPG use.
 */
public final class MysticIdentityPortal implements AutoCloseable {
    private static final String PROVIDER_CLASS = "org.hyzionstudios.mysticidentity.MysticIdentityProvider";
    private static final long ATTACH_INTERVAL_SECONDS = 5L;

    private final QuestPortalSource source;
    private final HytaleLogger logger;
    private final ScheduledExecutorService attacher;
    private volatile PortalRegistration registration;
    private volatile boolean closed;

    private MysticIdentityPortal(QuestPortalSource source, HytaleLogger logger) {
        this.source = source;
        this.logger = logger;
        this.attacher = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-Portal");
            thread.setDaemon(true);
            return thread;
        });
    }

    /** @return the hook when MysticIdentity is on the classpath, otherwise empty */
    public static Optional<MysticIdentityPortal> start(QuestPortalSource source, HytaleLogger logger) {
        try {
            Class.forName(PROVIDER_CLASS, false, MysticIdentityPortal.class.getClassLoader());
        } catch (ClassNotFoundException | LinkageError absent) {
            return Optional.empty();
        }
        MysticIdentityPortal portal = new MysticIdentityPortal(source, logger);
        portal.attacher.scheduleWithFixedDelay(portal::tryAttach, 0L, ATTACH_INTERVAL_SECONDS, TimeUnit.SECONDS);
        return Optional.of(portal);
    }

    /** Whether the provider is registered with a live MysticIdentity right now, for {@code /mq integrations}. */
    public boolean registered() {
        PortalRegistration current = registration;
        try {
            return current != null && current.stillRegistered();
        } catch (LinkageError | RuntimeException gone) {
            return false;
        }
    }

    private void tryAttach() {
        if (closed) {
            return;
        }
        try {
            PortalRegistration current = registration;
            if (current != null && current.stillRegistered()) {
                return;
            }
            if (current != null) {
                current.close();
                registration = null;
            }
            PortalRegistration attached = PortalRegistration.attach(source);
            if (attached == null) {
                return;
            }
            registration = attached;
            logger.at(Level.INFO).log("MysticQuests registered with the MysticIdentity player portal.");
        } catch (LinkageError | RuntimeException notReady) {
            // Not published yet, or an API build without the portal; try again on the next tick.
        }
    }

    @Override
    public void close() {
        closed = true;
        attacher.shutdownNow();
        PortalRegistration current = registration;
        registration = null;
        if (current != null) {
            current.close();
        }
    }
}
