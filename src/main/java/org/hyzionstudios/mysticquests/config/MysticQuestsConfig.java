package org.hyzionstudios.mysticquests.config;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

public record MysticQuestsConfig(
        StorageConfig storage,
        String packagesPath,
        IntegrationConfig integrations,
        StateConfig state,
        UiConfig ui,
        boolean debug) {
    public static MysticQuestsConfig defaults() {
        return new MysticQuestsConfig(
                new StorageConfig("sqlite", "data/players", "data/mysticquests.db"),
                "packages",
                new IntegrationConfig(true, true, true, true, true, true),
                new StateConfig(true, true),
                new UiConfig(true, UiConfig.DEFAULT_HUD_JOIN_DELAY_MILLIS),
                false);
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
                state == null ? defaults.state : state,
                ui == null ? defaults.ui : ui.withDefaults(),
                debug);
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

    public record IntegrationConfig(
            boolean vaultUnlocked,
            boolean placeholderApi,
            boolean mysticNameTags,
            boolean hyExtras,
            Boolean hyCitizens,
            Boolean hyExtrasExportPlayerState) {
        IntegrationConfig withDefaults() {
            return new IntegrationConfig(
                    vaultUnlocked,
                    placeholderApi,
                    mysticNameTags,
                    hyExtras,
                    hyCitizens == null ? true : hyCitizens,
                    hyExtrasExportPlayerState == null ? true : hyExtrasExportPlayerState);
        }
    }

    public record StateConfig(
            boolean migrateLegacyPlayerTags,
            boolean migrateLegacyPlayerVariables) {
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
