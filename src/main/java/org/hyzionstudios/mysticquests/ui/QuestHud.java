package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The tracked-quest HUD, in a compact or an expanded composition.
 *
 * <p>Both compositions live in one document, so a mode change from {@link QuestHudCoordinator} is a
 * small Visible patch rather than a HUD removal and re-add. The puzzle card is a separate layer,
 * {@link QuestPuzzleHud}. Full quest history remains in the Journal.
 */
public final class QuestHud extends CustomUIHud {
    public static final String KEY = "mysticquests:quest_tracker";
    /** Appended path; the client resolves it against {@code Common/UI/Custom/}. */
    public static final String DOCUMENT = "Hud/MysticQuestsQuestHud.ui";
    /** Same document as a classpath resource, so probe builds that omit it can be detected. */
    public static final String DOCUMENT_RESOURCE = "/Common/UI/Custom/" + DOCUMENT;
    /** Supporting rows the expanded composition declares. */
    private static final int SUPPORTING_ROWS = 3;

    private QuestHudViewModel model;

    public QuestHud(PlayerRef playerRef, QuestHudViewModel model) {
        super(playerRef, KEY, 40);
        this.model = model;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        // Hud/ is the shared custom-HUD root, the layout MysticRPG's working HUD uses. Do not file
        // this under the mod's own folder: a HUD append resolving from mysticquests/… is what
        // disconnected clients with "Could not find document … for Custom UI Append command".
        builder.append(DOCUMENT);
        appendState(builder);
    }

    public void updateModel(QuestHudViewModel model) {
        this.model = model;
        UICommandBuilder builder = new UICommandBuilder();
        appendState(builder);
        update(false, builder);
    }

    private void appendState(UICommandBuilder builder) {
        boolean expanded = model.displayMode() == QuestHudViewModel.DisplayMode.EXPANDED;
        builder.set("#CompactTracker.Visible", !expanded);
        builder.set("#ExpandedTracker.Visible", expanded);

        builder.set("#TrackedCategory.Text", model.categoryLabel());
        builder.set("#TrackedState.Text", model.stateLabel());
        builder.set("#TrackedQuestName.Text", model.questTitle());
        builder.set("#TrackedContext.Text", model.contextLabel());
        builder.set("#TrackedObjectiveText.Text", model.objectiveText());
        builder.set("#TrackedObjectiveProgress.Text", model.objectiveProgressLabel());
        builder.set("#TrackedObjectiveMeter.Value", model.objectiveProgressRatio());
        builder.set("#TrackedQuestProgress.Text", model.questProgressLabel());
        builder.set("#TrackedGuidance.Text", model.guidanceLabel());

        builder.set("#ExpandedCategory.Text", model.categoryLabel());
        builder.set("#ExpandedState.Text", model.stateLabel());
        builder.set("#ExpandedQuestName.Text", model.questTitle());
        builder.set("#ExpandedContext.Text", model.contextLabel());
        builder.set("#ExpandedPrimaryText.Text", model.objectiveText());
        builder.set("#ExpandedPrimaryProgress.Text", model.objectiveProgressLabel());
        builder.set("#ExpandedPrimaryMeter.Value", model.objectiveProgressRatio());
        builder.set("#ExpandedGuidance.Text", model.guidanceLabel());
        builder.set("#ExpandedQuestProgress.Text", model.questProgressLabel());
        builder.set("#ExpandedActionHint.Text", model.actionHint());

        for (int index = 0; index < SUPPORTING_ROWS; index++) {
            boolean present = index < model.supportingObjectives().size();
            builder.set("#ExpandedRow" + index + ".Visible", present);
            if (!present) {
                continue;
            }
            QuestHudViewModel.ObjectiveRow row = model.supportingObjectives().get(index);
            builder.set("#ExpandedRowRole" + index + ".Text", row.roleLabel());
            builder.set("#ExpandedRowText" + index + ".Text", row.objectiveText());
            builder.set("#ExpandedRowProgress" + index + ".Text", row.progressLabel());
            builder.set("#ExpandedRowMeter" + index + ".Value", row.progressRatio());
        }
    }
}
