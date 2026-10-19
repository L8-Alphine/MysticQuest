package org.hyzionstudios.mysticquests.external;

import java.util.Map;
import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

/**
 * Titles of finished quests by language, so a bridge words each one once instead of on every read.
 * Bridges are read about once a second for every player, and a player can have finished hundreds of
 * quests whose titles never change.
 *
 * <p>The cache belongs to one generation of the mod's quest content, named by an object the mod
 * replaces when it reloads its quests (its catalog, its engine). A different owner starts the cache
 * over, so a renamed quest does not keep its old title. Safe from any thread.
 */
public final class TitleCache {
    private record Generation(Object owner, Map<String, Map<String, String>> byLanguage) {
    }

    private volatile Generation generation = new Generation(new Object(), new ConcurrentHashMap<>());

    /**
     * The quest's title in {@code language}, worded by {@code resolve} the first time it is asked for.
     *
     * @param owner the mod's quest content the title comes from; compared by identity
     * @param resolve words the title for a quest id; null reads as empty
     */
    public String title(Object owner, String language, String questId, Function<String, String> resolve) {
        Generation current = generation;
        if (current.owner() != owner) {
            current = new Generation(owner, new ConcurrentHashMap<>());
            generation = current;
        }
        return current.byLanguage()
                .computeIfAbsent(language == null ? "" : language, ignored -> new ConcurrentHashMap<>())
                .computeIfAbsent(questId, id -> Objects.requireNonNullElse(resolve.apply(id), ""));
    }
}
