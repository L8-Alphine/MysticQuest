package org.hyzionstudios.mysticquests.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record MysticQuestsConfig(
        StorageConfig storage,
        String packagesPath,
        IntegrationConfig integrations,
        StateConfig state,
        UiConfig ui,
        boolean debug,
        NarrativeConfig narrative) {
    public static MysticQuestsConfig defaults() {
        return new MysticQuestsConfig(
                new StorageConfig("sqlite", "data/players", "data/mysticquests.db"),
                "packages",
                new IntegrationConfig(true, true, true, true, true, true, true, true),
                new StateConfig(true, true, StateConfig.DEFAULT_SAVE_INTERVAL_MILLIS),
                new UiConfig(true, UiConfig.DEFAULT_HUD_JOIN_DELAY_MILLIS),
                false,
                NarrativeConfig.DEFAULTS);
    }

    public static MysticQuestsConfig load(Path dataDirectory, ObjectMapper mapper) throws IOException {
        Path configPath = dataDirectory.resolve("config.json");
        if (Files.notExists(configPath)) {
            MysticQuestsConfig defaults = defaults();
            Files.createDirectories(dataDirectory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), defaults);
            return defaults;
        }
        MysticQuestsConfig config = mapper.readValue(configPath.toFile(), MysticQuestsConfig.class);
        return config.withDefaults();
    }

    private MysticQuestsConfig withDefaults() {
        MysticQuestsConfig defaults = defaults();
        return new MysticQuestsConfig(
                storage == null ? defaults.storage : storage.withDefaults(),
                packagesPath == null || packagesPath.isBlank() ? defaults.packagesPath : packagesPath,
                integrations == null ? defaults.integrations : integrations.withDefaults(),
                state == null ? defaults.state : state.withDefaults(),
                ui == null ? defaults.ui : ui.withDefaults(),
                debug,
                narrative == null ? defaults.narrative : narrative.withDefaults());
    }

    /**
     * The 2.0 narrative runtime: sessions, typed state, logical trigger activation, puzzles.
     *
     * @param serverId this server's stable id. It owns server-scope state and global trigger
     *         overrides, and is recorded on every session it touches. Give each server on a network
     *         its own id, and never change it once players have state, or that state becomes
     *         unreachable.
     * @param dataPath where narrative documents are stored, relative to the data directory. Point
     *         several servers at one shared location to carry sessions across server transfers.
     * @param flushIntervalMillis how often pending narrative changes are written; quit and shutdown
     *         always write immediately
     * @param partyExitPolicy {@code fork} (a leaver keeps a solo copy of the party's stories) or
     *         {@code detach} (the story stays with the party)
     * @param openNamespaces namespaces where undeclared tags and variables are accepted, besides
     *         {@code legacy}. Meant for migration only: in an open namespace a typo creates a new
     *         variable instead of failing the reload.
     */
    public record NarrativeConfig(
            String serverId,
            String dataPath,
            Integer flushIntervalMillis,
            String partyExitPolicy,
            List<String> openNamespaces,
            /* Where subtitles appear: chat, title or off. */
            String subtitles,
            /* The locale every localised voice line must have; heard when the player's own is missing. */
            String fallbackLocale) {
        static final NarrativeConfig DEFAULTS =
                new NarrativeConfig("default", "data/narrative", 1000, "fork", List.of(), "chat", "en-US");

        NarrativeConfig withDefaults() {
            return new NarrativeConfig(
                    serverId == null || serverId.isBlank() ? DEFAULTS.serverId : serverId,
                    dataPath == null || dataPath.isBlank() ? DEFAULTS.dataPath : dataPath,
                    flushIntervalMillis == null || flushIntervalMillis <= 0 ? DEFAULTS.flushIntervalMillis : flushIntervalMillis,
                    partyExitPolicy == null || partyExitPolicy.isBlank() ? DEFAULTS.partyExitPolicy : partyExitPolicy,
                    openNamespaces == null ? DEFAULTS.openNamespaces : List.copyOf(openNamespaces),
                    subtitles == null || subtitles.isBlank() ? DEFAULTS.subtitles : subtitles,
                    fallbackLocale == null || fallbackLocale.isBlank() ? DEFAULTS.fallbackLocale : fallbackLocale);
        }
    }

    public record StorageConfig(String type, String jsonPath, String sqlitePath) {
        StorageConfig withDefaults() {
            StorageConfig defaults = defaults().storage();
            return new StorageConfig(
                    type == null || type.isBlank() ? defaults.type : type,
                    jsonPath == null || jsonPath.isBlank() ? defaults.jsonPath : jsonPath,
                    sqlitePath == null || sqlitePath.isBlank() ? defaults.sqlitePath : sqlitePath);
        }
    }

    /**
     * @param mysticGeneration whether quests may bind to NPCs authored in MysticGeneration's Studio.
     *         On by default and harmless when that mod is absent — the bridge simply never binds —
     *         so this exists to switch the integration off on a server that runs both mods but wants
     *         them kept apart.
     * @param mysticVanish whether quest visibility defers to MysticVanish. Both mods hide players
     *         through the same shared engine set, so with this off MysticQuests can lift a vanish it
     *         did not apply. On by default; turn it off only to reproduce that older behaviour.
     */
    public record IntegrationConfig(
            boolean vaultUnlocked,
            boolean placeholderApi,
            boolean mysticNameTags,
            boolean hyExtras,
            Boolean hyCitizens,
            Boolean hyExtrasExportPlayerState,
            Boolean mysticGeneration,
            Boolean mysticVanish) {
        IntegrationConfig withDefaults() {
            return new IntegrationConfig(
                    vaultUnlocked,
                    placeholderApi,
                    mysticNameTags,
                    hyExtras,
                    hyCitizens == null ? true : hyCitizens,
                    hyExtrasExportPlayerState == null ? true : hyExtrasExportPlayerState,
                    mysticGeneration == null ? true : mysticGeneration,
                    mysticVanish == null ? true : mysticVanish);
        }
    }

    /**
     * @param saveIntervalMillis how often pending tag and variable changes are flushed to storage.
     *         State mutations are coalesced per owner inside this window instead of writing on every
     *         change, so a quest that updates a counter every tick costs one write per window rather
     *         than one per tick. Disconnect and shutdown always flush synchronously regardless, so
     *         raising this trades crash-loss window for fewer writes, not correctness on clean stop.
     */
    public record StateConfig(
            boolean migrateLegacyPlayerTags,
            boolean migrateLegacyPlayerVariables,
            Integer saveIntervalMillis) {
        static final int DEFAULT_SAVE_INTERVAL_MILLIS = 1000;

        StateConfig withDefaults() {
            return new StateConfig(
                    migrateLegacyPlayerTags,
                    migrateLegacyPlayerVariables,
                    saveIntervalMillis == null || saveIntervalMillis <= 0
                            ? DEFAULT_SAVE_INTERVAL_MILLIS
                            : saveIntervalMillis);
        }
    }

    /**
     * @param questHud whether the pinned quest HUD is shown at all. Turn it off to keep players on
     *         the server while a "Could not find document …" disconnect is being diagnosed: a failed
     *         HUD append kicks the player, so a mod that cannot render its HUD is better off silent.
     * @param hudJoinDelayMillis how long after a player is ready before the quest HUD is pushed.
     *         A HUD sent on the ready tick itself arrives while the client is still registering the
     *         asset pack's UI documents, and the append fails with "Could not find document …" for a
     *         document the client already has on disk. Lower it if the HUD feels late; raise it if
     *         that disconnect comes back.
     */
    public record UiConfig(Boolean questHud, Integer hudJoinDelayMillis) {
        static final int DEFAULT_HUD_JOIN_DELAY_MILLIS = 3000;

        UiConfig withDefaults() {
            return new UiConfig(
                    questHud == null || questHud,
                    hudJoinDelayMillis == null || hudJoinDelayMillis < 0
                            ? DEFAULT_HUD_JOIN_DELAY_MILLIS
                            : hudJoinDelayMillis);
        }
    }
}
