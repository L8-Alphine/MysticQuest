package org.hyzionstudios.mysticquests.event;

import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Quest lifecycle events posted on the {@link MysticQuestsEventBus} (MysticRPG request Q-1).
 *
 * <p>Every event of one quest run carries the same {@code completionId}. It is derived from the
 * player, the quest and the moment the run started ({@link #completionId}), and the start time is
 * persisted with the active quest in both storage backends, so the id is stable across restarts and
 * replay-safe: the same run always reports the same id, and a later run of the same quest always
 * reports a different one. Consumers that grant something (MysticRPG's trial evidence) key their
 * idempotency on it.
 *
 * <p>Events are posted after the state change is saved, on the thread that made the change (usually
 * the world thread), exactly once per transition: a second {@code completeQuest} of a quest that is
 * no longer active is refused and posts nothing.
 */
public final class QuestLifecycleEvents {
    private QuestLifecycleEvents() {
    }

    /** Version of this event contract; bumped when a record changes shape. */
    public static final int VERSION = 1;

    /**
     * The completion id of one run of one quest for one player: a name-based UUID (version 3) over
     * {@code mysticquests:run:<player>|<questId>|<startedAt>}, as a string.
     */
    public static String completionId(UUID player, String questId, Instant startedAt) {
        String name = "mysticquests:run:" + player + "|" + questId + "|" + startedAt;
        return UUID.nameUUIDFromBytes(name.getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** A quest became active for a player. */
    public record QuestStarted(UUID player, String questId, String completionId, Instant startedAt) {
    }

    /**
     * A quest was completed: every required objective is done and its completion events ran.
     *
     * @param objectiveProgress progress of every objective at completion, including optional ones,
     *                          so a consumer can score them
     */
    public record QuestCompleted(UUID player, String questId, String completionId, Instant startedAt,
                                 Instant completedAt, Map<String, Integer> objectiveProgress) {
        public QuestCompleted {
            objectiveProgress = objectiveProgress == null ? Map.of() : Map.copyOf(objectiveProgress);
        }
    }

    /** A quest was failed (a time limit, too many deaths, or {@code failQuest} from content or code). */
    public record QuestFailed(UUID player, String questId, String completionId, Instant startedAt,
                              Instant failedAt, String reason) {
    }

    /**
     * A quest was abandoned: by the player, by a staff reset, by a canceler, or because the player
     * left during a trial.
     */
    public record QuestAbandoned(UUID player, String questId, String completionId, Instant startedAt,
                                 Instant abandonedAt, String reason) {
    }
}
