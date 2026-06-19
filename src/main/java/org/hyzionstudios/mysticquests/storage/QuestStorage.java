package org.hyzionstudios.mysticquests.storage;

import java.io.IOException;
import java.util.UUID;

public interface QuestStorage extends AutoCloseable {
    PlayerQuestData loadPlayer(UUID playerId) throws IOException;

    void savePlayer(PlayerQuestData data) throws IOException;

    ScopedStateData loadScopedState() throws IOException;

    void saveScopedState(ScopedStateData data) throws IOException;

    @Override
    void close() throws IOException;
}
