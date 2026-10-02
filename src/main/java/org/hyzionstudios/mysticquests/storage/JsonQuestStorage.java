package org.hyzionstudios.mysticquests.storage;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;

import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;
import org.hyzionstudios.mysticquests.state.StateSnapshot;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.Collection;
import java.util.EnumMap;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class JsonQuestStorage implements QuestStorage {
    /** One file per scope, keyed owner id to entry. The layout predates the state rework and is kept. */
    private static final Map<StateScope, String> STATE_FILES = new EnumMap<>(Map.of(
            StateScope.PLAYER, "players.json",
            StateScope.GLOBAL, "global.json",
            StateScope.ENTITY, "entities.json",
            StateScope.BLOCK, "blocks.json",
            StateScope.VOLUME, "volumes.json"));

    private final Path directory;
    private final Path stateDirectory;
    private final ObjectMapper mapper;

    /**
     * Mirror of what is on disk, so a delta write can merge the changed owners into their scope file
     * without re-reading it. Writes touch only the scopes that actually changed.
     */
    private final Map<StateScope, Map<String, ScopedStateData.ScopedEntry>> onDisk = new EnumMap<>(StateScope.class);

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
    public synchronized List<StateSnapshot> loadState() throws IOException {
        List<StateSnapshot> snapshots = new ArrayList<>();
        for (StateScope scope : StateScope.values()) {
            Map<String, ScopedStateData.ScopedEntry> entries = readEntries(STATE_FILES.get(scope));
            onDisk.put(scope, entries);
            entries.forEach((owner, entry) -> snapshots.add(new StateSnapshot(
                    StateKey.of(scope, owner),
                    Set.copyOf(entry.tags()),
                    Map.copyOf(entry.variables()),
                    Map.copyOf(entry.metadata()))));
        }
        return snapshots;
    }

    @Override
    public synchronized void writeState(Collection<StateSnapshot> snapshots) throws IOException {
        if (snapshots.isEmpty()) {
            return;
        }
        Set<StateScope> touched = EnumSet.noneOf(StateScope.class);
        for (StateSnapshot snapshot : snapshots) {
            StateScope scope = snapshot.scope();
            Map<String, ScopedStateData.ScopedEntry> entries =
                    onDisk.computeIfAbsent(scope, ignored -> new LinkedHashMap<>());
            if (snapshot.isEmpty()) {
                entries.remove(snapshot.owner());
            } else {
                entries.put(snapshot.owner(), toEntry(snapshot));
            }
            touched.add(scope);
        }

        Files.createDirectories(stateDirectory);
        for (StateScope scope : touched) {
            mapper.writerWithDefaultPrettyPrinter()
                    .writeValue(stateDirectory.resolve(STATE_FILES.get(scope)).toFile(), onDisk.get(scope));
        }
    }

    private static ScopedStateData.ScopedEntry toEntry(StateSnapshot snapshot) {
        ScopedStateData.ScopedEntry entry = new ScopedStateData.ScopedEntry();
        entry.setTags(new LinkedHashSet<>(snapshot.tags()));
        entry.setVariables(new LinkedHashMap<>(snapshot.variables()));
        entry.setMetadata(new LinkedHashMap<>(snapshot.metadata()));
        return entry;
    }

    private Map<String, ScopedStateData.ScopedEntry> readEntries(String fileName) throws IOException {
        Path file = stateDirectory.resolve(fileName);
        if (Files.notExists(file)) {
            return new LinkedHashMap<>();
        }
        return mapper.readValue(file.toFile(),
                new TypeReference<LinkedHashMap<String, ScopedStateData.ScopedEntry>>() { });
    }

    @Override
    public void close() {
    }
}
