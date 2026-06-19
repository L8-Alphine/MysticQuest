package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;

import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.BasicCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.util.List;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

public final class MysticQuestJournalPage extends BasicCustomUIPage {
    private static final Pattern PROGRESS_PATTERN = Pattern.compile("(\\d+)\\s*/\\s*(\\d+)\\s*$");

    private final PlayerQuestService questService;
    private final UUID playerId;

    public MysticQuestJournalPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction);
        this.playerId = playerId;
        this.questService = questService;
    }

    @Override
    public void build(UICommandBuilder builder) {
        builder.append("mysticquests/Pages/JournalPage.ui");

        PlayerQuestData data = questService.data(playerId);
        List<JournalEntry> entries = questService.journal(playerId);
        builder.set("#ActiveCount.Text", entries.size());
        builder.set("#CompletedCount.Text", data.completedQuests().size());

        if (entries.isEmpty()) {
            builder.set("#EmptyState.Visible", true);
            builder.set("#QuestDetails.Visible", false);
            return;
        }

        JournalEntry selected = entries.getFirst();
        builder.set("#EmptyState.Visible", false);
        builder.set("#QuestDetails.Visible", true);
        builder.set("#SelectedQuestName.Text", selected.displayName());
        builder.set("#SelectedQuestId.Text", selected.questId());
        builder.set("#SelectedQuestDescription.Text", selected.description());

        appendQuestRows(builder, entries);
        appendObjectiveRows(builder, selected);
    }

    private void appendQuestRows(UICommandBuilder builder, List<JournalEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            JournalEntry entry = entries.get(index);
            String rowId = "QuestRow" + index;
            builder.appendInline("#QuestList", questRow(rowId, index == 0));
            builder.set("#" + rowId + " #QuestTitle.Text", entry.displayName());
            builder.set("#" + rowId + " #QuestProgress.Text", progressSummary(entry));
        }
    }

    private void appendObjectiveRows(UICommandBuilder builder, JournalEntry entry) {
        for (int index = 0; index < entry.objectives().size(); index++) {
            ObjectiveText objective = objectiveText(entry.objectives().get(index));
            String rowId = "ObjectiveRow" + index;
            builder.appendInline("#ObjectiveList", objectiveRow(rowId, objective.complete()));
            builder.set("#" + rowId + " #ObjectiveName.Text", objective.name());
            builder.set("#" + rowId + " #ObjectiveProgress.Text", objective.progress());
        }
    }

    private String progressSummary(JournalEntry entry) {
        int completed = 0;
        for (String objective : entry.objectives()) {
            if (objectiveText(objective).complete()) {
                completed++;
            }
        }
        return completed + " / " + entry.objectives().size() + " objectives";
    }

    private ObjectiveText objectiveText(String objective) {
        Matcher matcher = PROGRESS_PATTERN.matcher(objective);
        boolean complete = false;
        String progress = "";
        if (matcher.find()) {
            int current = Integer.parseInt(matcher.group(1));
            int target = Integer.parseInt(matcher.group(2));
            complete = target > 0 && current >= target;
            progress = current + " / " + target;
        }

        String name = objective;
        int colon = objective.lastIndexOf(':');
        if (colon >= 0) {
            name = objective.substring(0, colon).trim();
        } else if (!progress.isEmpty()) {
            name = objective.substring(0, matcher.start()).trim();
        }
        return new ObjectiveText(name, progress, complete);
    }

    private String questRow(String rowId, boolean selected) {
        String accent = selected ? "#F5C842" : "#000000(0)";
        String titleColor = selected ? "#F5C842" : "#EEF3FC";
        String background = selected ? "#1E2B3E" : "#000000(0)";
        return """
                Group #%s {
                  Anchor: (Height: 62, Bottom: 2);
                  LayoutMode: Left;
                  Background: %s;

                  Group {
                    Anchor: (Width: 4);
                    Background: %s;
                  }

                  Group {
                    FlexWeight: 1;
                    LayoutMode: Top;
                    Padding: (Left: 12, Right: 10, Top: 8);

                    Label #QuestTitle {
                      Text: "";
                      Style: (FontSize: 14, RenderBold: true, TextColor: %s, Wrap: true);
                      Anchor: (Bottom: 3);
                    }

                    Label #QuestProgress {
                      Text: "";
                      Style: (FontSize: 11, TextColor: #5E7898);
                    }
                  }
                }
                """.formatted(rowId, background, accent, titleColor);
    }

    private String objectiveRow(String rowId, boolean complete) {
        String dotBackground = complete ? "#3DD68C" : "#5E7898";
        String textColor = complete ? "#3DD68C" : "#9AAFC7";
        String progressColor = complete ? "#3DD68C" : "#F59E3A";
        return """
                Group #%s {
                  Anchor: (Height: 32, Bottom: 4);
                  LayoutMode: Left;

                  Group {
                    Anchor: (Width: 22);
                    LayoutMode: Center;

                    Group {
                      Anchor: (Width: 10, Height: 10);
                      Background: %s;
                    }
                  }

                  Label #ObjectiveName {
                    Text: "";
                    Style: (FontSize: 14, TextColor: %s, Wrap: true);
                    FlexWeight: 1;
                  }

                  Label #ObjectiveProgress {
                    Text: "";
                    Style: (FontSize: 12, RenderBold: true, TextColor: %s);
                    Anchor: (Left: 12);
                  }
                }
                """.formatted(rowId, dotBackground, textColor, progressColor);
    }

    private record ObjectiveText(String name, String progress, boolean complete) {
    }
}
