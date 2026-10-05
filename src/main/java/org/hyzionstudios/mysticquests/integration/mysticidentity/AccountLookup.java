package org.hyzionstudios.mysticquests.integration.mysticidentity;

import org.hyzionstudios.mysticidentity.MysticIdentityProvider;
import org.hyzionstudios.mysticidentity.api.identity.IdentityId;
import org.hyzionstudios.mysticidentity.api.service.PlayerIdentity;

import java.util.Optional;
import java.util.UUID;

/** The only class naming MysticIdentity's identity types; loaded only once {@link IdentityAccounts#present()} is true. */
final class AccountLookup {
    private AccountLookup() {
    }

    static Optional<String> accountOf(UUID player) {
        return MysticIdentityProvider.get()
                .flatMap(api -> api.players().lookup(player))
                .filter(PlayerIdentity::linked)
                .flatMap(PlayerIdentity::identityId)
                .map(IdentityId::toString);
    }
}
