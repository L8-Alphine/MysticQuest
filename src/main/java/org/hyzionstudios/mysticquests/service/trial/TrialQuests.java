package org.hyzionstudios.mysticquests.service.trial;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.PackageMetadata;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestResult;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

/**
 * What the trial service needs from the quest engine. The runtime binds it to
 * {@link PlayerQuestService}; tests bind it to the same service or to a fake.
 */
public interface TrialQuests {

    /** Every loaded quest by {@code <package>:<quest>}. */
    Map<String, QuestDefinition> quests();

    QuestResult start(UUID player, String questId);

    QuestResult abandon(UUID player, String questId, String reason);

    QuestResult fail(UUID player, String questId, String reason);

    /** Forgets earlier completion and abandonment records of a quest that is not active, so it can run again. */
    void clearRecords(UUID player, String questId);

    Optional<Instant> activeSince(UUID player, String questId);

    void message(UUID player, String text);

    /** The {@code version} of a loaded package's {@code package.yml}; empty when it names none. */
    String packageVersion(String packageId);

    /**
     * A copy of an active quest's progress for the shared trial store (R-1 handoff): when it started,
     * objective progress and quest variables. Empty when the quest is not active.
     */
    default Optional<PlayerQuestService.QuestSnapshot> snapshot(UUID player, String questId) {
        return Optional.empty();
    }

    /**
     * Makes a quest active again exactly as another server left it (R-1 continuation): no start
     * events, no lifecycle event, the same start time and so the same completion id.
     */
    default QuestResult restore(UUID player, String questId, PlayerQuestService.QuestSnapshot snapshot) {
        return QuestResult.failure("restoring trial progress is not supported here");
    }

    /** Drops an active quest without abandoning it (the run continues on another server). */
    default void detach(UUID player, String questId) {
    }

    static TrialQuests of(PlayerQuestService service, Supplier<LoadedContent> content,
                          QuestNotificationService notifications) {
        return new TrialQuests() {
            @Override
            public Map<String, QuestDefinition> quests() {
                return content.get().quests();
            }

            @Override
            public String packageVersion(String packageId) {
                PackageMetadata metadata = content.get().packageMetadata().get(packageId);
                return metadata == null || metadata.version() == null ? "" : metadata.version();
            }

            @Override
            public QuestResult start(UUID player, String questId) {
                return service.startQuestAsTrial(player, questId);
            }

            @Override
            public QuestResult abandon(UUID player, String questId, String reason) {
                return service.abandonQuest(player, questId, reason);
            }

            @Override
            public QuestResult fail(UUID player, String questId, String reason) {
                return service.failQuest(player, questId, reason);
            }

            @Override
            public void clearRecords(UUID player, String questId) {
                if (service.activeSince(player, questId).isEmpty()) {
                    service.resetQuestState(player, questId);
                }
            }

            @Override
            public Optional<Instant> activeSince(UUID player, String questId) {
                return service.activeSince(player, questId);
            }

            @Override
            public Optional<PlayerQuestService.QuestSnapshot> snapshot(UUID player, String questId) {
                return service.snapshotQuest(player, questId);
            }

            @Override
            public QuestResult restore(UUID player, String questId, PlayerQuestService.QuestSnapshot snapshot) {
                return service.restoreTrialQuest(player, questId, snapshot);
            }

            @Override
            public void detach(UUID player, String questId) {
                service.detachTrialQuest(player, questId);
            }

            @Override
            public void message(UUID player, String text) {
                if (notifications != null) {
                    notifications.sendChat(player, text, "#EEF3FC");
                }
            }
        };
    }
}
