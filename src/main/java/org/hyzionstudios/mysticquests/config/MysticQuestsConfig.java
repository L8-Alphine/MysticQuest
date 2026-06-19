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
        boolean debug) {
    public static MysticQuestsConfig defaults() {
        return new MysticQuestsConfig(
                new StorageConfig("sqlite", "data/players", "data/mysticquests.db"),
                "packages",
                new IntegrationConfig(true, true, true, true, true),
                new StateConfig(true, true),
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
            Boolean hyExtrasExportPlayerState) {
        IntegrationConfig withDefaults() {
            return new IntegrationConfig(
                    vaultUnlocked,
                    placeholderApi,
                    mysticNameTags,
                    hyExtras,
                    hyExtrasExportPlayerState == null ? true : hyExtrasExportPlayerState);
        }
    }

    public record StateConfig(
            boolean migrateLegacyPlayerTags,
            boolean migrateLegacyPlayerVariables) {
    }
}
