package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;

import java.util.ArrayList;
import java.util.List;

/**
 * Capability status of every optional integration (§20 of the 2.0 specification): whether it is
 * present, whether it is switched on, and what degrades without it.
 *
 * <p>The bridges stay where they are, each binding reflectively and degrading on its own. This is
 * the single place that asks them, so {@code /mq integrations} can say exactly which features a
 * server has, instead of staff inferring it from missing behaviour.
 */
public final class IntegrationStatus {
    public enum State {
        /** Installed and in use. */
        ACTIVE,
        /** Installed, but working with reduced capability; the detail says what is missing. */
        PARTIAL,
        /** Switched off in config.json. */
        DISABLED,
        /** Not installed; the detail says what degrades. */
        ABSENT
    }

    public record Entry(String name, State state, String detail) {
    }

    /** MysticNameTags' hook for MysticQuests visibility; present from the release that consults it. */
    static final String NAMETAGS_API = "com.mystichorizons.mysticnametags.api.MysticNameTagsAPI";
    static final String NAMETAGS_QUESTS_HOOK = "com.mystichorizons.mysticnametags.integrations.MysticQuestsSupport";

    private IntegrationStatus() {
    }

    public static List<Entry> collect(MysticQuestsRuntime runtime) {
        MysticQuestsConfig.IntegrationConfig flags = runtime.config().integrations();
        List<Entry> entries = new ArrayList<>();

        MysticVanishBridge vanish = runtime.vanishBridge();
        entries.add(!Boolean.TRUE.equals(flags.mysticVanish())
                ? new Entry("MysticVanish", State.DISABLED, "quest releases may lift a vanish they did not place")
                : vanish != null && vanish.isAvailable()
                        ? new Entry("MysticVanish", State.ACTIVE, "quest visibility never lifts a vanish")
                        : new Entry("MysticVanish", State.ABSENT, "nothing to defer to"));

        entries.add(nameTags(flags.mysticNameTags()));

        MysticPartyIntegration parties = runtime.partyIntegration();
        String provider = parties == null ? "none" : parties.providerName();
        entries.add(parties == null || !parties.available()
                ? new Entry("Parties", State.ABSENT, "party quests, party scope and party story sessions are unavailable")
                : parties.supportsPartyIds()
                        ? new Entry("Parties", State.ACTIVE, provider + "; party story sessions available")
                        : new Entry("Parties", State.PARTIAL, provider
                                + " gives members but no party id; shared objectives work, party story sessions fall back to each player"));

        MysticGenerationBridge generation = runtime.generationBridge();
        entries.add(generation == null || !generation.enabled()
                ? new Entry("MysticGeneration", State.DISABLED, "spawnNpc, despawnNpc and generation targets do nothing")
                : generation.available()
                        ? new Entry("MysticGeneration", State.ACTIVE, "NPCs addressable by stable identity")
                        : new Entry("MysticGeneration", State.ABSENT, "spawnNpc, despawnNpc and generation targets do nothing"));

        HyCitizensBridge citizens = runtime.hyCitizensBridge();
        entries.add(citizens == null || !citizens.enabled()
                ? new Entry("HyCitizens", State.DISABLED, "citizen conversations are not bridged")
                : citizens.available()
                        ? new Entry("HyCitizens", State.ACTIVE, "citizens open MysticQuests conversations")
                        : new Entry("HyCitizens", State.ABSENT, "citizen conversations are not bridged"));

        entries.add(scope("MysticIdentity", VariableScope.ACCOUNT, runtime));
        entries.add(present("VaultUnlocked", "net.cfh.vault.VaultUnlocked", flags.vaultUnlocked(),
                "economy conditions fail and money rewards are skipped"));
        entries.add(present("PlaceholderAPI", "at.helpch.placeholderapi.PlaceholderAPI", flags.placeholderApi(),
                "third-party placeholders are left unresolved"));
        return entries;
    }

    /**
     * MysticNameTags draws its glyph nameplates per viewer by packet, outside the engine's tracker,
     * so quest hiding reaches them only if MysticNameTags asks MysticQuests, the same way it asks
     * MysticVanish. Releases that do not ask are reported as partial: hidden players' glyphs stay
     * visible.
     */
    private static Entry nameTags(boolean enabled) {
        if (!classPresent(NAMETAGS_API)) {
            return new Entry("MysticNameTags", State.ABSENT, "engine nameplates are hidden by MysticQuests itself");
        }
        if (!enabled) {
            return new Entry("MysticNameTags", State.DISABLED, "glyph nameplates ignore quest visibility");
        }
        return classPresent(NAMETAGS_QUESTS_HOOK)
                ? new Entry("MysticNameTags", State.ACTIVE, "glyph nameplates follow quest visibility per viewer")
                : new Entry("MysticNameTags", State.PARTIAL,
                        "this release does not consult MysticQuests; quest-hidden players keep their glyph nameplate");
    }

    private static Entry scope(String name, VariableScope scope, MysticQuestsRuntime runtime) {
        if (runtime.narrative() == null) {
            return new Entry(name, State.ABSENT, scope.id() + " scope unavailable");
        }
        ScopeSupport.Status status = runtime.narrative().runtime().resolver().support().status(scope);
        return status.level() == ScopeSupport.Level.SUPPORTED
                ? new Entry(name, State.ACTIVE, scope.id() + " scope available")
                : new Entry(name, State.ABSENT, scope.id() + " scope: " + status.reason());
    }

    private static Entry present(String name, String className, boolean enabled, String degradation) {
        if (!enabled) {
            return new Entry(name, State.DISABLED, degradation);
        }
        return classPresent(className) ? new Entry(name, State.ACTIVE, "installed") : new Entry(name, State.ABSENT, degradation);
    }

    private static boolean classPresent(String className) {
        try {
            Class.forName(className, false, IntegrationStatus.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }
}
