package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

/**
 * The bottom-centre puzzle card: its own HUD layer, shown whether or not a quest is tracked.
 *
 * <p>It renders a {@link QuestPuzzleHudState}, which the server has already redacted to a title, a
 * revealed hint, a semantic status and feedback — never input ids, candidates or solution order.
 */
public final class QuestPuzzleHud extends CustomUIHud {
    public static final String KEY = "mysticquests:puzzle_card";
    /** Appended relative to the client's Custom UI root, beside the tracker's own document. */
    public static final String DOCUMENT = "Hud/MysticQuestsPuzzleHud.ui";
    /** Same document as a classpath resource, so probe builds that omit it can be detected. */
    public static final String DOCUMENT_RESOURCE = "/Common/UI/Custom/" + DOCUMENT;

    private QuestPuzzleHudState state;

    public QuestPuzzleHud(PlayerRef playerRef, QuestPuzzleHudState state) {
        super(playerRef, KEY, 40);
        this.state = state;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append(DOCUMENT);
        appendState(builder);
    }

    public void updateState(QuestPuzzleHudState state) {
        this.state = state;
        UICommandBuilder builder = new UICommandBuilder();
        appendState(builder);
        update(false, builder);
    }

    private void appendState(UICommandBuilder builder) {
        builder.set("#PuzzleTitle.Text", state.title());
        builder.set("#PuzzleHint.Text", state.hintText());
        builder.set("#PuzzleStatus.Text", state.statusText());
        builder.set("#PuzzleSession.Text", state.sessionLabel());
        builder.set("#PuzzleState.Text", state.phase().label());
        builder.set("#PuzzleFeedback.Text", state.feedback());
        builder.set("#PuzzleFeedback.Visible", !state.feedback().isEmpty());
    }
}
