package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.api.objective.QuestObjectives;
import org.hyzionstudios.mysticquests.api.objective.TrackedObjectivesChanged;
import org.hyzionstudios.mysticquests.api.objective.TrackedQuest;
import org.hyzionstudios.mysticquests.api.objective.TrackingResult;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;

import org.hyzionstudios.mysticquests.model.QuestDefinition;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;
import java.util.function.Consumer;

/**
 * {@link QuestObjectives} on the quest engine (MysticRPG PO addendum P-62): the tracked quest and its
 * current step as plain data for another mod's HUD, track and untrack, change events, and the tracker
 * claim that hides MysticQuests' own tracker panel while another mod draws one. Hidden quests
 * ({@code hidden: true}) never leave this class.
 */
public final class ObjectiveTrackingService implements QuestObjectives {

    /** Said for an unknown, inactive or hidden quest alike, so nobody can probe for a hidden one. */
    static final String NOT_ACTIVE = "That quest is not one of your active quests.";

    private final PlayerQuestService quests;
    private final MysticQuestsEventBus bus;
    private final Set<String> claims = ConcurrentHashMap.newKeySet();
    private final AtomicLong claimIds = new AtomicLong();
    private volatile Runnable claimsChanged = () -> { };

    public ObjectiveTrackingService(PlayerQuestService quests, MysticQuestsEventBus bus) {
        this.quests = quests;
        this.bus = bus;
        quests.addChangeListener(player -> bus.post(new TrackedObjectivesChanged(player, "quest")));
    }

    /** Called whenever a tracker claim starts or ends (the runtime reconciles MysticQuests' HUD). */
    public void onClaimsChanged(Runnable listener) {
        this.claimsChanged = listener == null ? () -> { } : listener;
    }

    /** Whether another mod draws the tracker right now. */
    public boolean trackerClaimed() {
        return !claims.isEmpty();
    }

    /** After a content reload: every listed player's tracker may show something else now. */
    public void reloaded(Iterable<UUID> players) {
        for (UUID player : players) {
            bus.post(new TrackedObjectivesChanged(player, "reload"));
        }
    }

    @Override
    public Optional<TrackedQuest> tracked(UUID player) {
        if (player == null) {
            return Optional.empty();
        }
        JournalEntry entry = quests.trackedJournalEntry(player);
        if (entry == null) {
            return Optional.empty();
        }
        Optional<QuestDefinition> definition = quests.definition(entry.questId());
        if (definition.isEmpty() || definition.get().hidden()) {
            return Optional.empty();
        }
        return Optional.of(view(entry, definition.get(), true));
    }

    @Override
    public List<TrackedQuest> active(UUID player) {
        if (player == null) {
            return List.of();
        }
        String trackedId = quests.data(player).trackedQuestId();
        List<TrackedQuest> visible = new ArrayList<>();
        List<JournalEntry> entries = new ArrayList<>(quests.journal(player));
        entries.sort(Comparator.comparing((JournalEntry entry) ->
                quests.activeSince(player, entry.questId()).orElse(Instant.MAX)).thenComparing(JournalEntry::questId));
        for (JournalEntry entry : entries) {
            Optional<QuestDefinition> definition = quests.definition(entry.questId());
            if (definition.isPresent() && !definition.get().hidden()) {
                visible.add(view(entry, definition.get(), entry.questId().equals(trackedId)));
            }
        }
        return visible;
    }

    @Override
    public TrackingResult track(UUID player, String questId) {
        if (player == null || questId == null || !visibleAndActive(player, questId)) {
            return TrackingResult.refused(NOT_ACTIVE);
        }
        if (questId.equals(quests.data(player).trackedQuestId())) {
            return TrackingResult.unchanged("You are already tracking that quest.");
        }
        QuestResult result = quests.trackQuest(player, questId);
        return result.success() ? TrackingResult.done("Tracking " + displayName(questId) + ".")
                : TrackingResult.refused(NOT_ACTIVE);
    }

    @Override
    public TrackingResult untrack(UUID player, String questId) {
        if (player == null || questId == null || !visibleAndActive(player, questId)) {
            return TrackingResult.refused(NOT_ACTIVE);
        }
        if (!questId.equals(quests.data(player).trackedQuestId())) {
            return TrackingResult.unchanged("That quest is not tracked.");
        }
        quests.untrackQuest(player);
        return TrackingResult.done("Stopped tracking " + displayName(questId) + ".");
    }

    @Override
    public AutoCloseable subscribe(Consumer<TrackedObjectivesChanged> listener) {
        return bus.subscribe(TrackedObjectivesChanged.class, listener);
    }

    @Override
    public AutoCloseable claimTracker(String owner) {
        String claim = (owner == null || owner.isBlank() ? "unnamed" : owner) + "#" + claimIds.incrementAndGet();
        claims.add(claim);
        claimsChanged.run();
        return () -> {
            if (claims.remove(claim)) {
                claimsChanged.run();
            }
        };
    }

    private boolean visibleAndActive(UUID player, String questId) {
        Optional<QuestDefinition> definition = quests.definition(questId);
        return definition.isPresent() && !definition.get().hidden()
                && quests.questStatus(player, questId) == PlayerQuestService.QuestStatus.ACTIVE;
    }

    private String displayName(String questId) {
        return quests.definition(questId).map(QuestDefinition::displayName).orElse(questId);
    }

    private static TrackedQuest view(JournalEntry entry, QuestDefinition definition, boolean tracked) {
        StageView stage = entry.currentStage();
        List<ObjectiveView> shown = stage == null ? entry.objectives() : stage.objectives();
        List<TrackedQuest.Objective> objectives = new ArrayList<>();
        StringBuilder revision = new StringBuilder(entry.questId()).append('|').append(tracked);
        for (ObjectiveView objective : shown) {
            boolean optional = definition.objectives().stream()
                    .filter(candidate -> candidate.id().equals(objective.objectiveId()))
                    .findFirst()
                    .map(candidate -> candidate.bool("optional", false))
                    .orElse(false);
            objectives.add(new TrackedQuest.Objective(objective.objectiveId(), objective.displayName(),
                    objective.current(), objective.target(), objective.complete(), optional));
            revision.append('|').append(objective.objectiveId()).append(':').append(objective.current())
                    .append('/').append(objective.target());
        }
        String stageName = entry.grouped() && stage != null ? stage.displayName() : "";
        revision.append('|').append(stageName);
        return new TrackedQuest(entry.questId(), definition.displayName(), definition.category(), stageName,
                objectives, definition.trial() != null, tracked,
                UUID.nameUUIDFromBytes(revision.toString().getBytes(StandardCharsets.UTF_8)).toString());
    }
}
