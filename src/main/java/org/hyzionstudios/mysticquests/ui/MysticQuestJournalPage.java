package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.List;
import java.util.UUID;

public final class MysticQuestJournalPage extends InteractiveCustomUIPage<MysticQuestJournalPage.PageEventData> {
    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .addField(new KeyedCodec<>("Quest", Codec.STRING), PageEventData::setQuest, PageEventData::quest)
            .addField(new KeyedCodec<>("Tab", Codec.STRING), PageEventData::setTab, PageEventData::tab)
            .addField(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action)
            .build();

    private final PlayerQuestService questService;
    private final UUID playerId;
    private Tab selectedTab = Tab.CURRENT;
    private String selectedQuestId;
    /** Quest awaiting a second Abandon press; cleared on any other interaction. */
    private String abandonPendingQuestId;

    public MysticQuestJournalPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EVENT_CODEC);
        this.playerId = playerId;
        this.questService = questService;
    }

    @Override
    public void build(Ref<EntityStore> playerEntity, UICommandBuilder builder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        render(builder, eventBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerEntity, Store<EntityStore> store, PageEventData data) {
        if (!data.tab().isBlank()) {
            selectedTab = Tab.from(data.tab());
            selectedQuestId = null;
            abandonPendingQuestId = null;
        }
        if (!data.quest().isBlank() && !data.quest().equals(selectedQuestId)) {
            selectedQuestId = data.quest();
            abandonPendingQuestId = null;
        }
        if (selectedQuestId != null && selectedTab == Tab.CURRENT) {
            switch (data.action().toLowerCase()) {
                case "track" -> questService.trackQuest(playerId, selectedQuestId);
                case "abandon" -> {
                    // Second press confirms; the button relabels itself in between (bible 4.2).
                    if (selectedQuestId.equals(abandonPendingQuestId)) {
                        questService.abandonQuest(playerId, selectedQuestId);
                        abandonPendingQuestId = null;
                        selectedQuestId = null;
                    } else {
                        abandonPendingQuestId = selectedQuestId;
                    }
                }
                default -> abandonPendingQuestId = null;
            }
        }
        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        render(builder, eventBuilder);
        sendUpdate(builder, eventBuilder, true);
    }

    private void render(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.append("mysticquests/Pages/JournalPage.ui");

        PlayerQuestData data = questService.data(playerId);
        builder.set("#ActiveCount.Text", Integer.toString(data.activeQuests().size()));
        builder.set("#CompletedCount.Text", Integer.toString(data.completedQuests().size()));

        for (Tab tab : Tab.values()) {
            appendTab(builder, eventBuilder, tab);
        }

        List<JournalEntry> entries = entries();
        if (entries.isEmpty()) {
            builder.set("#EmptyState.Visible", true);
            builder.set("#QuestDetails.Visible", false);
            builder.set("#EmptyStateTitle.Text", selectedTab.emptyTitle());
            builder.set("#EmptyStateBody.Text", selectedTab.emptyDescription());
            builder.set("#TrackButton.Visible", false);
            builder.set("#AbandonButton.Visible", false);
            return;
        }

        JournalEntry selected = entries.stream()
                .filter(entry -> entry.questId().equals(selectedQuestId))
                .findFirst()
                .orElse(entries.getFirst());
        selectedQuestId = selected.questId();

        builder.set("#EmptyState.Visible", false);
        builder.set("#QuestDetails.Visible", true);
        builder.set("#SelectedQuestName.Text", selected.displayName());
        builder.set("#SelectedQuestId.Text", selected.questId());
        builder.set("#SelectedQuestDescription.Text", selected.description());

        appendQuestRows(builder, eventBuilder, entries);
        appendObjectiveRows(builder, selected);

        boolean current = selectedTab == Tab.CURRENT;
        builder.set("#TrackButton.Visible", current);
        builder.set("#AbandonButton.Visible", current);
        if (current) {
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#TrackButton",
                    EventData.of("Action", "track"));
            builder.set(
                    "#AbandonButton.Text",
                    selected.questId().equals(abandonPendingQuestId) ? "CONFIRM ABANDON" : "ABANDON");
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#AbandonButton",
                    EventData.of("Action", "abandon"));
        }
    }

    private List<JournalEntry> entries() {
        return switch (selectedTab) {
            case CURRENT -> questService.journal(playerId);
            case COMPLETED -> questService.completedJournal(playerId);
            case ABANDONED -> questService.abandonedJournal(playerId);
        };
    }

    private void appendTab(UICommandBuilder builder, UIEventBuilder eventBuilder, Tab tab) {
        boolean active = selectedTab == tab;
        String id = "Tab" + tab.name();
        builder.appendInline("#TabList", """
                Button #%s {
                  Anchor: (Height: 34, Bottom: 2);
                  LayoutMode: Left;
                  Padding: (Horizontal: 16);
                  Style: %s;

                  Label #TabName {
                    Text: "%s";
                    Style: (FontSize: 14, RenderBold: true, TextColor: %s, VerticalAlignment: Center);
                  }
                }
                """.formatted(
                id,
                MysticQuestsTheme.rowButtonStyle(active),
                uiText(tab.label()),
                active ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_SECONDARY));
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#" + id,
                EventData.of("Tab", tab.name()));
    }

    private void appendQuestRows(UICommandBuilder builder, UIEventBuilder eventBuilder, List<JournalEntry> entries) {
        for (int index = 0; index < entries.size(); index++) {
            JournalEntry entry = entries.get(index);
            String rowId = "QuestRow" + index;
            builder.appendInline("#QuestList", questRow(
                    rowId,
                    entry.questId().equals(selectedQuestId),
                    entry.displayName(),
                    entry.progressSummary()));
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + rowId,
                    EventData.of("Quest", entry.questId()));
        }
    }

    private void appendObjectiveRows(UICommandBuilder builder, JournalEntry entry) {
        for (int index = 0; index < entry.objectives().size(); index++) {
            builder.appendInline("#ObjectiveList", objectiveRow("ObjectiveRow" + index, entry.objectives().get(index)));
        }
    }

    private String questRow(String rowId, boolean selected, String title, String progress) {
        String accent = selected ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TRANSPARENT;
        String titleColor = selected ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_PRIMARY;
        return """
                Button #%s {
                  Anchor: (Height: 56, Bottom: 2);
                  LayoutMode: Left;
                  Style: %s;

                  Group {
                    Anchor: (Width: 4);
                    Background: %s;
                  }

                  Group {
                    FlexWeight: 1;
                    LayoutMode: Top;
                    Padding: (Left: 12, Right: 10, Top: 6);

                    Label #QuestTitle {
                      Text: "%s";
                      Style: (FontSize: 14, RenderBold: true, TextColor: %s, Wrap: true);
                      Anchor: (Bottom: 3);
                    }

                    Label #QuestProgress {
                      Text: "%s";
                      Style: (FontSize: 11, TextColor: %s);
                    }
                  }
                }
                """.formatted(
                rowId,
                MysticQuestsTheme.rowButtonStyle(selected),
                accent,
                uiText(title),
                titleColor,
                uiText(progress),
                MysticQuestsTheme.TEXT_MUTED);
    }

    private String objectiveRow(String rowId, ObjectiveView objective) {
        boolean complete = objective.complete();
        String textColor = complete ? MysticQuestsTheme.ACCENT_GREEN : MysticQuestsTheme.TEXT_SECONDARY;
        String progressColor = complete ? MysticQuestsTheme.ACCENT_GREEN : MysticQuestsTheme.ACCENT_ORANGE;
        String state = complete ? "DONE" : "ACTIVE";
        return """
                Group #%s {
                  Anchor: (Height: 32, Bottom: 4);
                  LayoutMode: Left;
                  Background: %s;
                  Padding: (Horizontal: 8);

                  AssetImage #%sIcon {
                    AssetPath: "%s";
                    Anchor: (Width: 22, Height: 22, Right: 7);
                  }

                  Label #%sState {
                    Text: "%s";
                    Style: (FontSize: 9, RenderBold: true, TextColor: %s);
                    Anchor: (Width: 42, Right: 6);
                  }

                  Label #%sName {
                    Text: "%s";
                    Style: (FontSize: 13, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 10);
                    FlexWeight: 1;
                  }

                  Label #%sProgress {
                    Text: "%s";
                    Style: (FontSize: 11, RenderBold: true, TextColor: %s);
                    Anchor: (Width: 46, Left: 8);
                  }
                }
                """.formatted(
                rowId,
                complete ? MysticQuestsTheme.PANEL_RAISED : MysticQuestsTheme.PANEL_SOFT,
                rowId,
                complete
                        ? "UI/Custom/mysticquests/Assets/Icons/status/quest_complete_32.png"
                        : "UI/Custom/mysticquests/Assets/Icons/navigation/tracker_32.png",
                rowId,
                state,
                textColor,
                rowId,
                uiText(objective.displayName()),
                textColor,
                rowId,
                uiText(objective.progressLabel()),
                progressColor);
    }

    private String uiText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private enum Tab {
        CURRENT("Current", "No active quests",
                "Accept a quest with /quest, or speak with a quest giver."),
        COMPLETED("Completed", "No completed quests",
                "Quests you finish are recorded here."),
        ABANDONED("Abandoned", "No abandoned quests",
                "Quests you drop are recorded here, along with when they can be taken again.");

        private final String label;
        private final String emptyTitle;
        private final String emptyDescription;

        Tab(String label, String emptyTitle, String emptyDescription) {
            this.label = label;
            this.emptyTitle = emptyTitle;
            this.emptyDescription = emptyDescription;
        }

        String label() {
            return label;
        }

        String emptyTitle() {
            return emptyTitle;
        }

        String emptyDescription() {
            return emptyDescription;
        }

        static Tab from(String value) {
            for (Tab tab : values()) {
                if (tab.name().equalsIgnoreCase(value)) {
                    return tab;
                }
            }
            return CURRENT;
        }
    }

    public static final class PageEventData {
        private String quest;
        private String tab;
        private String action;

        public String quest() {
            return quest == null ? "" : quest;
        }

        public void setQuest(String quest) {
            this.quest = quest;
        }

        public String tab() {
            return tab == null ? "" : tab;
        }

        public void setTab(String tab) {
            this.tab = tab;
        }

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
