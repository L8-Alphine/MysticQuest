package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.StageView;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.List;

/**
 * The pinned quest tracker.
 *
 * <p>The row list shows one step of the quest — the first with work left — rather than the quest's
 * first few objectives, because a long quest's later objectives are noise until the player gets
 * there. What the rows leave out, the block above them supplies: which step this is, and how much of
 * the whole quest is done. A quest with ungrouped objectives has exactly one step, so it reads as it
 * always did, with the step line hidden.
 */
public final class QuestHud extends CustomUIHud {
    public static final String KEY = "mysticquests:quest_tracker";
    /** Appended path; the client resolves it against {@code Common/UI/Custom/}. */
    public static final String DOCUMENT = "Hud/MysticQuestsQuestHud.ui";
    /** Same document as a classpath resource, so probe builds that omit it can be detected. */
    public static final String DOCUMENT_RESOURCE = "/Common/UI/Custom/" + DOCUMENT;
    /** Objective rows declared in the document; a step with more of them says how many are hidden. */
    private static final int MAX_OBJECTIVES = 5;
    private static final String ACTIVE_ICON =
            "UI/Custom/mysticquests/Assets/Icons/navigation/tracker_32.png";
    private static final String COMPLETE_ICON =
            "UI/Custom/mysticquests/Assets/Icons/status/quest_complete_32.png";

    private JournalEntry entry;

    public QuestHud(PlayerRef playerRef, JournalEntry entry) {
        super(playerRef, KEY, 40);
        this.entry = entry;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        // Hud/ is the shared custom-HUD root, the layout MysticRPG's working HUD uses. Do not file
        // this under the mod's own folder: a HUD append resolving from mysticquests/… is what
        // disconnected clients with "Could not find document … for Custom UI Append command".
        builder.append(DOCUMENT);
        appendState(builder);
    }

    public void updateEntry(JournalEntry entry) {
        this.entry = entry;
        UICommandBuilder builder = new UICommandBuilder();
        appendState(builder);
        update(false, builder);
    }

    private void appendState(UICommandBuilder builder) {
        builder.set("#TrackedQuestName.Text", entry.displayName());
        builder.set("#TrackedQuestId.Text", entry.questId());
        appendProgress(builder);
        appendObjectives(builder, visibleObjectives());
    }

    /** Whole-quest progress, plus which step the rows below belong to. */
    private void appendProgress(UICommandBuilder builder) {
        StageView stage = entry.currentStage();
        boolean grouped = entry.grouped() && stage != null;
        builder.set("#TrackedStepLabel.Text", grouped ? stage.stepLabel() : "");
        builder.set("#TrackedStageName.Text", grouped ? stage.displayName() : "");
        builder.set("#TrackedTotalProgress.Text", entry.progressLabel());
        builder.set("#TrackedTotalMeter.Value", entry.progressRatio());
    }

    /** The current step's objectives, or every objective when the quest has no steps. */
    private List<ObjectiveView> visibleObjectives() {
        StageView stage = entry.currentStage();
        return stage == null ? entry.objectives() : stage.objectives();
    }

    private void appendObjectives(UICommandBuilder builder, List<ObjectiveView> objectives) {
        int visibleCount = Math.min(MAX_OBJECTIVES, objectives.size());
        for (int index = 0; index < MAX_OBJECTIVES; index++) {
            String rowId = "#HudObjective" + index;
            boolean visible = index < visibleCount;
            builder.set(rowId + ".Visible", visible);
            if (!visible) {
                builder.set("#HudObjectiveText" + index + ".Text", "");
                builder.set("#HudObjectiveState" + index + ".Text", "");
                builder.set("#HudObjectiveProgress" + index + ".Text", "");
                builder.set("#HudObjectiveMeter" + index + ".Value", 0.0f);
                continue;
            }
            ObjectiveView objective = objectives.get(index);
            boolean complete = objective.complete();
            // Shape, label, number and fill all carry state; meaning never rests on colour alone.
            builder.set("#HudObjectiveText" + index + ".Text", objective.displayName());
            builder.set("#HudObjectiveState" + index + ".Text", complete ? "DONE" : "GO");
            builder.set("#HudObjectiveProgress" + index + ".Text", objective.progressLabel());
            builder.set("#HudObjectiveIcon" + index + ".AssetPath", complete ? COMPLETE_ICON : ACTIVE_ICON);
            builder.set("#HudObjectiveMeter" + index + ".Value",
                    (float) objective.current() / (float) Math.max(1, objective.target()));
        }
        int hidden = objectives.size() - visibleCount;
        builder.set("#HudObjectiveOverflow.Visible", hidden > 0);
        builder.set("#HudObjectiveOverflow.Text", hidden > 0 ? "+ " + hidden + " MORE" : "");
    }
}
