package org.hyzionstudios.mysticquests.integration.mysticidentity;

import java.util.Optional;
import java.util.UUID;
import java.util.function.Function;

/**
 * The {@code account} scope's owner (§4.2, §19): the MysticIdentity identity a player's Hytale
 * account is linked to, so state a story keeps "for the person" follows them across every game
 * account they link, and survives relinking a different Discord account.
 *
 * <p>MysticIdentity is optional. The lookup is only built when its classes are present, and it is
 * a cache read on MysticIdentity's side, safe on the game thread. A player who is not linked, or
 * whose identity the network has not reported yet, has no account: account-scoped reads are false
 * and writes are skipped retryably, as a party-scoped write is for a player with no party.
 */
public final class IdentityAccounts {
    private static final String PROVIDER_CLASS = "org.hyzionstudios.mysticidentity.MysticIdentityProvider";

    private IdentityAccounts() {
    }

    /** Whether MysticIdentity is installed, so the account scope can resolve for linked players. */
    public static boolean present() {
        try {
            Class.forName(PROVIDER_CLASS, false, IdentityAccounts.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    /** Player to identity id; always empty when MysticIdentity is not installed. */
    public static Function<UUID, Optional<String>> resolver() {
        if (!present()) {
            return player -> Optional.empty();
        }
        return player -> {
            try {
                return AccountLookup.accountOf(player);
            } catch (LinkageError olderApi) {
                // A MysticIdentity from before identity ids: no account to key on.
                return Optional.empty();
            }
        };
    }
}
