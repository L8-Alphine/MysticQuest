package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;

import com.hypixel.hytale.server.core.entity.entities.player.hud.CustomUIHud;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

public final class QuestHud extends CustomUIHud {
    public static final String KEY = "mysticquests:quest_tracker";

    private final JournalEntry entry;

    public QuestHud(PlayerRef playerRef, JournalEntry entry) {
        super(playerRef, KEY, 40);
        this.entry = entry;
    }

    @Override
    protected void build(UICommandBuilder builder) {
        builder.append("mysticquests/Pages/QuestHud.ui");
        builder.appendInline("#HudQuestList", questBlock());
        builder.set("#TrackedQuestName.Text", entry.displayName());
        builder.set("#TrackedQuestId.Text", entry.questId());
        int maxObjectives = Math.min(4, entry.objectives().size());
        for (int index = 0; index < maxObjectives; index++) {
            String rowId = "HudObjective" + index;
            builder.appendInline("#TrackedObjectiveList", objectiveRow(rowId));
            builder.set("#" + rowId + " #ObjectiveText.Text", entry.objectives().get(index));
        }
    }

    private String questBlock() {
        return """
                Group #TrackedQuest {
                  LayoutMode: Top;

                  Label #TrackedQuestName {
                    Text: "";
                    Style: (FontSize: 17, RenderBold: true, TextColor: #EEF3FC, Wrap: true);
                    Anchor: (Bottom: 3);
                  }

                  Label #TrackedQuestId {
                    Text: "";
                    Style: (FontSize: 10, TextColor: #5E7898);
                    Anchor: (Bottom: 9);
                  }

                  Group #TrackedObjectiveList {
                    LayoutMode: Top;
                  }
                }
                """;
    }

    private String objectiveRow(String rowId) {
        return """
                Group #%s {
                  Anchor: (Height: 28, Bottom: 3);
                  LayoutMode: Left;

                  Group {
                    Anchor: (Width: 8, Height: 8, Right: 8);
                    Background: #F5C842;
                  }

                  Label #ObjectiveText {
                    Text: "";
                    Style: (FontSize: 12, TextColor: #9AAFC7, Wrap: true);
                    FlexWeight: 1;
                  }
                }
                """.formatted(rowId);
    }
}
