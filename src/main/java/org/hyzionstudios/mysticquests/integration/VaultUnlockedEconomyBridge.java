package org.hyzionstudios.mysticquests.integration;

import net.cfh.vault.VaultUnlocked;
import net.milkbowl.vault2.economy.Economy;
import net.milkbowl.vault2.economy.EconomyResponse;

import java.math.BigDecimal;
import java.util.Optional;
import java.util.UUID;

public final class VaultUnlockedEconomyBridge {
    private final boolean enabled;

    public VaultUnlockedEconomyBridge(boolean enabled) {
        this.enabled = enabled;
    }

    public boolean has(UUID playerId, String account, BigDecimal amount) {
        return economy()
                .map(economy -> economy.has(account, playerId, amount))
                .orElse(amount.signum() <= 0);
    }

    public boolean modify(UUID playerId, String account, BigDecimal amount) {
        Optional<Economy> economy = economy();
        if (economy.isEmpty()) {
            return amount.signum() == 0;
        }
        EconomyResponse response = amount.signum() >= 0
                ? economy.get().deposit(account, playerId, amount)
                : economy.get().withdraw(account, playerId, amount.abs());
        return response.transactionSuccess();
    }

    private Optional<Economy> economy() {
        if (!enabled) {
            return Optional.empty();
        }
        try {
            return VaultUnlocked.economy();
        } catch (NoClassDefFoundError | Exception ignored) {
            return Optional.empty();
        }
    }
}
