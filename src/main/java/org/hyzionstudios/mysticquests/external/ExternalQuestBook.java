package org.hyzionstudios.mysticquests.external;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;

/**
 * The latest snapshot of every mirrored mod for every online player, and which quest the player's
 * MysticQuests tracker follows.
 *
 * <p>Bridges write here from the player's world thread; the HUD, the Journal and quest conditions read
 * from anywhere. Snapshots are immutable and replaced whole, so a reader never sees half an update.
 *
 * <h2>Tracker focus</h2>
 * The tracker shows one quest. A player's own MysticQuests choice wins until they pick a mirrored
 * quest, which happens either in the Journal or by tracking it in the other mod's book: a quest that
 * newly shows up as tracked there takes the focus. Untracking it there, finishing it, or abandoning it
 * hands the focus back. A player with no MysticQuests quest tracked sees their most recently tracked
 * mirrored quest.
 *
 * <p>The other quests a player tracks in a mirrored mod are listed under it ({@link #tracked}), as
 * the mods' own trackers listed them.
 */
public final class ExternalQuestBook {
    /** A mirrored quest the player has finished, as the Journal's completed section lists it. */
    public record Finished(String source, String questId, String title, @Nullable Instant at) {
        public String key() {
            return ExternalQuestId.of(source, questId);
        }
    }

    /** What changed between two snapshots of one mod for one player. */
    public record Change(UUID player, @Nullable ExternalQuestSnapshot before, ExternalQuestSnapshot after) {
        /** Whether there was a snapshot to compare with; the first one after a join is no news. */
        public boolean baseline() {
            return before == null;
        }

        public List<String> newlyActive() {
            return before == null ? List.of() : after.active().stream()
                    .map(ExternalQuest::questId)
                    .filter(id -> !before.isActive(id) && !before.completed().contains(id))
                    .toList();
        }

        public List<String> newlyCompleted() {
            return before == null ? List.of() : after.completed().stream()
                    .filter(id -> !before.completed().contains(id))
                    .sorted()
                    .toList();
        }

        public List<String> newlyTracked() {
            return before == null ? List.of() : after.tracked().stream()
                    .filter(id -> !before.tracked().contains(id))
                    .toList();
        }

        public List<String> newlyUntracked() {
            return before == null ? List.of() : before.tracked().stream()
                    .filter(id -> !after.tracked().contains(id))
                    .toList();
        }

        /** How much each stat rose; stats that fell or stayed are left out. */
        public Map<String, Long> statGains() {
            if (before == null) {
                return Map.of();
            }
            Map<String, Long> gains = new LinkedHashMap<>();
            after.stats().forEach((key, value) -> {
                Long previous = before.stats().get(key);
                if (previous != null && value > previous) {
                    gains.put(key, value - previous);
                }
            });
            return gains;
        }
    }

    /** Marks a player's explicit choice of their MysticQuests quest. */
    private static final String MYSTIC_QUESTS = "";

    private final Map<UUID, Map<String, ExternalQuestSnapshot>> snapshots = new ConcurrentHashMap<>();
    /** The tracked mirrored quest's key, or {@link #MYSTIC_QUESTS}; absent when the player chose nothing. */
    private final Map<UUID, String> focus = new ConcurrentHashMap<>();
    private final List<Consumer<Change>> listeners = new java.util.concurrent.CopyOnWriteArrayList<>();

    /** Called with every change, on the thread that reported it. */
    public void onChange(Consumer<Change> listener) {
        listeners.add(listener);
    }

    /**
     * Records a mod's latest snapshot for a player.
     *
     * @return the change, or empty when nothing differs from the last snapshot
     */
    public Optional<Change> put(UUID player, ExternalQuestSnapshot snapshot) {
        Objects.requireNonNull(snapshot, "snapshot");
        ExternalQuestSnapshot[] previous = new ExternalQuestSnapshot[1];
        boolean[] changed = new boolean[1];
        snapshots.compute(player, (ignored, bySource) -> {
            Map<String, ExternalQuestSnapshot> next = bySource == null ? new LinkedHashMap<>() : new LinkedHashMap<>(bySource);
            previous[0] = next.put(snapshot.source(), snapshot);
            changed[0] = !snapshot.equals(previous[0]);
            // Never written after this; kept in insertion order so mods list in the order they were read.
            return Collections.unmodifiableMap(next);
        });
        if (!changed[0]) {
            return Optional.empty();
        }
        Change change = new Change(player, previous[0], snapshot);
        followTracking(change);
        for (Consumer<Change> listener : listeners) {
            listener.accept(change);
        }
        return Optional.of(change);
    }

    /** Drops one mod's snapshot for every player, when that mod stops being mirrored. */
    public void removeSource(String source) {
        snapshots.replaceAll((player, bySource) -> {
            Map<String, ExternalQuestSnapshot> next = new LinkedHashMap<>(bySource);
            next.remove(source);
            return Collections.unmodifiableMap(next);
        });
        focus.entrySet().removeIf(entry -> ExternalQuestId.parse(entry.getValue())
                .map(parsed -> parsed.source().equals(source)).orElse(false));
    }

    private void followTracking(Change change) {
        UUID player = change.player();
        String current = focus.get(player);
        List<String> tracked = change.newlyTracked();
        if (!tracked.isEmpty()) {
            focus.put(player, ExternalQuestId.of(change.after().source(), tracked.getLast()));
            return;
        }
        if (current == null || current.isEmpty()) {
            return;
        }
        ExternalQuestId.parse(current)
                .filter(parsed -> parsed.source().equals(change.after().source()))
                .filter(parsed -> !change.after().isActive(parsed.questId())
                        || change.newlyUntracked().contains(parsed.questId()))
                .ifPresent(gone -> focus.remove(player, current));
    }

    /** The player picked a mirrored quest in a MysticQuests surface. */
    public void focus(UUID player, String key) {
        if (ExternalQuestId.isExternal(key)) {
            focus.put(player, key);
        }
    }

    /** The player picked one of their MysticQuests quests, or stopped tracking a mirrored one. */
    public void focusMysticQuests(UUID player) {
        focus.put(player, MYSTIC_QUESTS);
    }

    /**
     * The mirrored quest the tracker should show, if any.
     *
     * @param mysticQuestTracked whether the player has a MysticQuests quest tracked
     */
    public Optional<ExternalQuest> trackerQuest(UUID player, boolean mysticQuestTracked) {
        String chosen = focus.get(player);
        if (chosen != null && !chosen.isEmpty()) {
            Optional<ExternalQuest> focused = find(player, chosen);
            if (focused.isPresent()) {
                return focused;
            }
        }
        if (mysticQuestTracked) {
            return Optional.empty();
        }
        // Nothing of their own to show: the mirrored quest they tracked most recently.
        for (ExternalQuestSnapshot snapshot : snapshots(player)) {
            for (String id : snapshot.tracked().reversed()) {
                Optional<ExternalQuest> quest = snapshot.quest(id);
                if (quest.isPresent()) {
                    return quest;
                }
            }
        }
        return Optional.empty();
    }

    /** The tracked id for Journal purposes: the focused mirrored quest's key, or null for MysticQuests' own. */
    @Nullable
    public String trackedKey(UUID player, boolean mysticQuestTracked) {
        return trackerQuest(player, mysticQuestTracked).map(ExternalQuest::key).orElse(null);
    }

    /**
     * Every mirrored quest the player tracks in its own mod and still has: mod by mod, most recently
     * tracked first. The focused quest is among them when it is tracked there.
     */
    public List<ExternalQuest> tracked(UUID player) {
        Map<String, ExternalQuest> tracked = new LinkedHashMap<>();
        for (ExternalQuestSnapshot snapshot : snapshots(player)) {
            for (String id : new LinkedHashSet<>(snapshot.tracked()).reversed()) {
                snapshot.quest(id).ifPresent(quest -> tracked.putIfAbsent(quest.key(), quest));
            }
        }
        return List.copyOf(tracked.values());
    }

    /**
     * Every mirrored quest the player has finished and is not doing again: mod by mod, by title.
     * A repeatable quest that is active again shows as active instead.
     */
    public List<Finished> finished(UUID player) {
        List<Finished> finished = new ArrayList<>();
        for (ExternalQuestSnapshot snapshot : snapshots(player)) {
            snapshot.completed().stream()
                    .filter(id -> !snapshot.isActive(id))
                    .map(id -> {
                        ExternalQuestSnapshot.Completion completion = snapshot.completion(id);
                        return new Finished(snapshot.source(), id, completion.title(), completion.at());
                    })
                    .sorted(Comparator.comparing(Finished::title, String.CASE_INSENSITIVE_ORDER).thenComparing(Finished::questId))
                    .forEach(finished::add);
        }
        return finished;
    }

    /** The player's snapshots, in the order their mods were first read. */
    public List<ExternalQuestSnapshot> snapshots(UUID player) {
        Map<String, ExternalQuestSnapshot> bySource = snapshots.get(player);
        return bySource == null ? List.of() : List.copyOf(bySource.values());
    }

    public Optional<ExternalQuestSnapshot> snapshot(UUID player, String source) {
        Map<String, ExternalQuestSnapshot> bySource = snapshots.get(player);
        return Optional.ofNullable(bySource == null ? null : bySource.get(source));
    }

    /** Every mirrored quest in progress, mod by mod. */
    public List<ExternalQuest> active(UUID player) {
        List<ExternalQuest> quests = new ArrayList<>();
        for (ExternalQuestSnapshot snapshot : snapshots(player)) {
            quests.addAll(snapshot.active());
        }
        return quests;
    }

    public Optional<ExternalQuest> find(UUID player, String key) {
        return ExternalQuestId.parse(key)
                .flatMap(parsed -> snapshot(player, parsed.source()).flatMap(snapshot -> snapshot.quest(parsed.questId())));
    }

    public boolean isActive(UUID player, String source, String questId) {
        return snapshot(player, source).map(snapshot -> snapshot.isActive(questId)).orElse(false);
    }

    public boolean isCompleted(UUID player, String source, String questId) {
        return snapshot(player, source).map(snapshot -> snapshot.completed().contains(questId)).orElse(false);
    }

    public Set<String> completed(UUID player, String source) {
        return snapshot(player, source).map(ExternalQuestSnapshot::completed).orElse(Set.of());
    }

    public OptionalLong stat(UUID player, String source, String key) {
        Long value = snapshot(player, source).map(snapshot -> snapshot.stats().get(key)).orElse(null);
        return value == null ? OptionalLong.empty() : OptionalLong.of(value);
    }

    public void forget(UUID player) {
        snapshots.remove(player);
        focus.remove(player);
    }

    public void clear() {
        snapshots.clear();
        focus.clear();
    }
}
