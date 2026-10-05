package org.hyzionstudios.mysticquests.integration.mysticidentity;

import org.hyzionstudios.mysticidentity.MysticIdentityProvider;
import org.hyzionstudios.mysticidentity.api.portal.PortalService;
import org.hyzionstudios.mysticidentity.api.service.MysticIdentityApi;

import java.util.Optional;

/**
 * The one place the MysticIdentity API is named outside the provider, loaded only after
 * {@link MysticIdentityPortal}'s classpath probe succeeds.
 */
final class PortalRegistration implements AutoCloseable {
    private final MysticIdentityApi api;
    private final PortalService portal;
    private final PortalService.Registration registration;

    private PortalRegistration(MysticIdentityApi api, PortalService portal, PortalService.Registration registration) {
        this.api = api;
        this.portal = portal;
        this.registration = registration;
    }

    /** Registers the provider; {@code null} while MysticIdentity has not published its API. */
    static PortalRegistration attach(QuestPortalSource source) {
        Optional<MysticIdentityApi> current = MysticIdentityProvider.get();
        if (current.isEmpty()) {
            return null;
        }
        Optional<PortalService> portal = current.get().portal();
        if (portal.isEmpty()) {
            return null;
        }
        PortalService.Registration registration = portal.get().register(new QuestsPortalProvider(source));
        return new PortalRegistration(current.get(), portal.get(), registration);
    }

    /** Whether the API and registration this holds are still the live ones. */
    boolean stillRegistered() {
        return MysticIdentityProvider.get().filter(live -> live == api).isPresent()
                && portal.isRegistered(QuestsPortalProvider.MODULE_ID);
    }

    @Override
    public void close() {
        try {
            registration.close();
        } catch (RuntimeException | LinkageError gone) {
            // MysticIdentity went first and took its registry with it.
        }
    }
}
