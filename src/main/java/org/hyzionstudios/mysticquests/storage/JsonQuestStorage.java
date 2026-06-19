package org.hyzionstudios.mysticquests.storage;

import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;

public final class JsonQuestStorage implements QuestStorage {
    private final Path directory;
    private final Path stateDirectory;
    private final ObjectMapper mapper;

    public JsonQuestStorage(Path directory, ObjectMapper mapper) throws IOException {
        this.directory = directory;
        this.stateDirectory = directory.getParent() == null ? directory.resolve("state") : directory.getParent().resolve("state");
        this.mapper = mapper;
        Files.createDirectories(directory);
        Files.createDirectories(stateDirectory);
    }

    @Override
    public PlayerQuestData loadPlayer(UUID playerId) throws IOException {
        Path file = directory.resolve(playerId + ".json");
        if (Files.notExists(file)) {
            return new PlayerQuestData(playerId);
        }
        PlayerQuestData data = mapper.readValue(file.toFile(), PlayerQuestData.class);
        if (data.playerId() == null) {
            data.setPlayerId(playerId);
        }
        return data;
    }

    @Override
    public void savePlayer(PlayerQuestData data) throws IOException {
        Files.createDirectories(directory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(directory.resolve(data.playerId() + ".json").toFile(), data);
    }

    @Override
    public ScopedStateData loadScopedState() throws IOException {
        ScopedStateData state = new ScopedStateData();
        state.setGlobal(readEntries("global.json"));
        state.setEntity(readEntries("entities.json"));
        state.setBlock(readEntries("blocks.json"));
        state.setVolume(readEntries("volumes.json"));
        state.setPlayer(readEntries("players.json"));
        return state;
    }

    @Override
    public void saveScopedState(ScopedStateData data) throws IOException {
        Files.createDirectories(stateDirectory);
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateDirectory.resolve("global.json").toFile(), data.global());
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateDirectory.resolve("entities.json").toFile(), data.entity());
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateDirectory.resolve("blocks.json").toFile(), data.block());
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateDirectory.resolve("volumes.json").toFile(), data.volume());
        mapper.writerWithDefaultPrettyPrinter().writeValue(stateDirectory.resolve("players.json").toFile(), data.player());
    }

    private java.util.Map<String, ScopedStateData.ScopedEntry> readEntries(String fileName) throws IOException {
        Path file = stateDirectory.resolve(fileName);
        if (Files.notExists(file)) {
            return new java.util.LinkedHashMap<>();
        }
        return mapper.readValue(file.toFile(), new com.fasterxml.jackson.core.type.TypeReference<java.util.LinkedHashMap<String, ScopedStateData.ScopedEntry>>() {});
    }

    @Override
    public void close() {
    }
}
