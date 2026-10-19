package org.hyzionstudios.mysticquests.external;

import java.util.List;

/**
 * One quest another mod runs, as MysticQuests mirrors it: already worded for the player, already
 * counted. The other mod stays in charge of it; nothing here feeds back into its state.
 *
 * @param source the mirroring bridge's id, such as {@code endgame}
 * @param questId the other mod's own id for the quest
 * @param category the Journal category it is filed under, such as {@code endgame}
 * @param stepIndex 1-based step the player is on
 * @param stepCount how many steps the quest has; 1 when the mod has no steps
 * @param stepName what the current step or chapter is called, or empty
 * @param objectives the objectives of the current step, plus any finished earlier ones the mod still lists
 * @param nextAction what the player has to do once every objective is done, such as
 *         {@code "Return to Vorthak"}; empty when the mod finishes the quest by itself
 * @param rewardText rewards the mod announces, or empty
 */
public record ExternalQuest(
        String source,
        String questId,
        String title,
        String description,
        String category,
        int stepIndex,
        int stepCount,
        String stepName,
        List<ExternalObjective> objectives,
        Status status,
        String nextAction,
        String rewardText) {

    public enum Status {
        /** Objectives still open. */
        ACTIVE,
        /** Every objective done; waiting for the player to hand in or claim. */
        READY
    }

    public ExternalQuest {
        title = title == null || title.isBlank() ? questId : title.strip();
        description = description == null ? "" : description.strip();
        category = category == null ? "" : category.strip();
        stepCount = Math.max(1, stepCount);
        stepIndex = Math.max(1, Math.min(stepIndex, stepCount));
        stepName = stepName == null ? "" : stepName.strip();
        objectives = List.copyOf(objectives);
        nextAction = nextAction == null ? "" : nextAction.strip();
        rewardText = rewardText == null ? "" : rewardText.strip();
    }

    /** The id MysticQuests surfaces use for this quest, {@code ext:<source>:<questId>}. */
    public String key() {
        return ExternalQuestId.of(source, questId);
    }
}
