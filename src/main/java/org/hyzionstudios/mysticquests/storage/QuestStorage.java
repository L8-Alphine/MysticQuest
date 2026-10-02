package org.hyzionstudios.mysticquests.storage;

import org.hyzionstudios.mysticquests.state.StateSnapshot;

import java.io.IOException;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

public interface QuestStorage extends AutoCloseable {
    PlayerQuestData loadPlayer(UUID playerId) throws IOException;

    void savePlayer(PlayerQuestData data) throws IOException;

    /** Reads every persisted state owner once at startup. */
    List<StateSnapshot> loadState() throws IOException;

    /**
     * Persists only the owners that changed. An {@link StateSnapshot#isEmpty() empty} snapshot means
     * the owner has no state left and its rows should be deleted.
     *
     * <p>This replaces the previous whole-table rewrite: implementations must touch only the owners
     * they are given, because this is called on every debounce tick while quests are running.
     */
    void writeState(Collection<StateSnapshot> snapshots) throws IOException;

    @Override
    void close() throws IOException;
}
