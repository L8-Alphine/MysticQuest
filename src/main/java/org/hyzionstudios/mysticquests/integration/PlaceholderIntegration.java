package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;

/**
 * Optional PlaceholderAPI hook.
 *
 * <p>Nothing in this class may name a PlaceholderAPI type. Verification loads every class a method
 * assigns or invokes against, so a single {@code PlaceholderExpansion} field or local here makes the
 * whole class fail to link on a server without the plugin — {@code NoClassDefFoundError} thrown from
 * the constructor, outside any {@code try}, taking MysticQuests down with it. All contact with the
 * API lives behind {@link PlaceholderExpansionFactory}, which is only loaded inside the guard below.
 */
public final class PlaceholderIntegration {
    private final boolean enabled;
    private final PlayerQuestService questService;
    private Runnable unregisterHook;

    public PlaceholderIntegration(boolean enabled, PlayerQuestService questService) {
        this.enabled = enabled;
        this.questService = questService;
    }

    public void register() {
        if (!enabled) {
            return;
        }
        try {
            unregisterHook = PlaceholderExpansionFactory.register(questService);
        } catch (NoClassDefFoundError | Exception ignored) {
            unregisterHook = null;
        }
    }

    public void unregister() {
        if (unregisterHook == null) {
            return;
        }
        try {
            unregisterHook.run();
        } catch (NoClassDefFoundError | Exception ignored) {
            // The expansion is going away with the server either way.
        }
        unregisterHook = null;
    }
}
