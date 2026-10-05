package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.StageView;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;
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

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Quest Journal (Redesign Bible §7.1-7.2, §7.4). {@link JournalModel} decides what shows; this
 * page draws it: the rail of sections and rows, the detail of the selected quest or story, and the
 * settings panel, which shares the detail pane.
 */
public final class MysticQuestJournalPage extends InteractiveCustomUIPage<MysticQuestJournalPage.PageEventData> {
    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .addField(new KeyedCodec<>("Kind", Codec.STRING), PageEventData::setKind, PageEventData::kind)
            .addField(new KeyedCodec<>("Id", Codec.STRING), PageEventData::setId, PageEventData::id)
            .addField(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action)
            .addField(new KeyedCodec<>("Setting", Codec.STRING), PageEventData::setSetting, PageEventData::setting)
            .addField(new KeyedCodec<>("Value", Codec.STRING), PageEventData::setValue, PageEventData::value)
            .build();

    private final PlayerQuestService questService;
    private final JournalSources sources;
    private final UUID playerId;
    private JournalModel.Kind selectedKind;
    private String selectedId;
    /** Quest awaiting a second Abandon press; cleared on any other interaction. */
    private String abandonPendingQuestId;

    public MysticQuestJournalPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService, JournalSources sources) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EVENT_CODEC);
        this.playerId = playerId;
        this.questService = questService;
        this.sources = sources;
    }

    @Override
    public void build(Ref<EntityStore> playerEntity, UICommandBuilder builder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        render(builder, eventBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerEntity, Store<EntityStore> store, PageEventData data) {
        if (!data.kind().isBlank()) {
            JournalModel.Kind kind = parseKind(data.kind());
            if (kind != selectedKind || !data.id().equals(selectedId)) {
                abandonPendingQuestId = null;
            }
            selectedKind = kind;
            selectedId = data.id();
        }
        if (!data.setting().isBlank()) {
            applySetting(data.setting(), data.value());
        }
        if (selectedKind == JournalModel.Kind.QUEST && selectedId != null && !data.action().isBlank()) {
            switch (data.action()) {
                case "track" -> questService.trackQuest(playerId, selectedId);
                case "untrack" -> questService.untrackQuest(playerId);
                case "abandon" -> {
                    // Second press confirms; the button relabels itself in between.
                    if (selectedId.equals(abandonPendingQuestId)) {
                        questService.abandonQuest(playerId, selectedId);
                        abandonPendingQuestId = null;
                        selectedId = null;
                    } else {
                        abandonPendingQuestId = selectedId;
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

    private void applySetting(String setting, String value) {
        switch (setting) {
            case "tracker" -> {
                try {
                    sources.setTracker(playerId, QuestHudCoordinator.Preference.valueOf(value));
                } catch (IllegalArgumentException unknown) {
                    // A stale page from before a change; ignore rather than guess.
                }
            }
            case "subtitles" -> sources.setSubtitles(playerId, value.equals("on"));
            case "voice" -> sources.followGameLanguage(playerId);
            case "popups" -> sources.setPopups(playerId, value.equals("on"));
            default -> {
                // Unknown setting from an older page; nothing to do.
            }
        }
    }

    // --- Rendering ---

    private JournalModel model() {
        PlayerQuestData data = questService.data(playerId);
        Map<String, Instant> startedAt = new LinkedHashMap<>();
        for (Map.Entry<String, ActiveQuestData> active : data.activeQuests().entrySet()) {
            startedAt.put(active.getKey(), active.getValue().startedAt());
        }
        List<JournalModel.Finished> completed = new ArrayList<>();
        for (JournalEntry entry : questService.completedJournal(playerId)) {
            completed.add(new JournalModel.Finished(entry, data.completedQuests().get(entry.questId())));
        }
        List<JournalModel.Finished> abandoned = new ArrayList<>();
        for (JournalEntry entry : questService.abandonedJournal(playerId)) {
            abandoned.add(new JournalModel.Finished(entry, data.abandonedQuests().get(entry.questId())));
        }
        return new JournalModel(questService.journal(playerId), data.trackedQuestId(), startedAt, completed, abandoned,
                sources.stories(playerId),
                id -> questService.definition(id).map(QuestDefinition::category).orElse(""),
                id -> questService.definition(id).map(QuestDefinition::rewardText).orElse(""));
    }

    private void render(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.append("mysticquests/Pages/JournalPage.ui");
        JournalModel model = model();
        PlayerQuestData data = questService.data(playerId);
        builder.set("#ActiveCount.Text", Integer.toString(data.activeQuests().size()));
        builder.set("#StoryCount.Text", Integer.toString(model.storyCount()));
        builder.set("#CompletedCount.Text", Integer.toString(data.completedQuests().size()));

        Optional<JournalModel.Item> selected = selectedKind == JournalModel.Kind.SETTINGS
                ? Optional.empty() : model.select(selectedKind, selectedId);
        selected.ifPresent(item -> {
            selectedKind = item.kind();
            selectedId = item.id();
        });
        appendRail(builder, eventBuilder, model, selected.orElse(null));
        appendSettingsEntry(builder, eventBuilder);

        builder.set("#SettingsPanel.Visible", selectedKind == JournalModel.Kind.SETTINGS);
        if (selectedKind == JournalModel.Kind.SETTINGS) {
            builder.set("#EmptyState.Visible", false);
            builder.set("#QuestDetails.Visible", false);
            appendSettings(builder, eventBuilder);
            return;
        }
        if (selected.isEmpty()) {
            builder.set("#EmptyState.Visible", true);
            builder.set("#QuestDetails.Visible", false);
            builder.set("#EmptyStateTitle.Text", "Your journal is empty");
            builder.set("#EmptyStateBody.Text", "Accept a quest with /quest, or speak with a quest giver.");
            return;
        }
        builder.set("#EmptyState.Visible", false);
        builder.set("#QuestDetails.Visible", true);
        if (selected.get().kind() == JournalModel.Kind.STORY) {
            model.story(selected.get().id()).ifPresent(story -> renderStory(builder, story));
        } else {
            model.quest(selected.get().id()).ifPresent(detail -> renderQuest(builder, eventBuilder, detail));
        }
    }

    private void appendRail(UICommandBuilder builder, UIEventBuilder eventBuilder, JournalModel model, JournalModel.Item selected) {
        int head = 0;
        int row = 0;
        for (JournalModel.Section section : model.sections()) {
            builder.appendInline("#RailList", sectionHeader("RailHead" + head++, section.title(), section.items().size()));
            for (JournalModel.Item item : section.items()) {
                String rowId = "RailRow" + row++;
                boolean isSelected = selected != null && item.kind() == selected.kind() && item.id().equals(selected.id());
                builder.appendInline("#RailList", railRow(rowId, isSelected, item));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#" + rowId,
                        EventData.of("Kind", item.kind().name()).append("Id", item.id()));
            }
        }
    }

    private void appendSettingsEntry(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        boolean active = selectedKind == JournalModel.Kind.SETTINGS;
        builder.appendInline("#RailFooter", """
                Button #SettingsEntry {
                  Anchor: (Height: 34);
                  LayoutMode: Left;
                  Padding: (Horizontal: 14);
                  Style: %s;

                  Label #SettingsEntryName {
                    Text: "QUEST SETTINGS";
                    Style: (FontSize: 13, RenderBold: true, TextColor: %s, VerticalAlignment: Center);
                  }
                }
                """.formatted(MysticQuestsTheme.rowButtonStyle(active), active ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_SECONDARY));
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#SettingsEntry",
                EventData.of("Kind", JournalModel.Kind.SETTINGS.name()).append("Id", "settings"));
    }

    private void renderQuest(UICommandBuilder builder, UIEventBuilder eventBuilder, JournalModel.QuestDetail detail) {
        JournalEntry entry = detail.entry();
        builder.set("#DetailCategory.Text", QuestCategories.badge(detail.category()));
        builder.set("#DetailState.Text", switch (detail.state()) {
            case TRACKED -> "TRACKED";
            case COMPLETE -> "COMPLETE";
            case ABANDONED -> "ABANDONED";
            default -> "ACTIVE";
        });
        builder.set("#DetailWhen.Text", detail.when() == null ? "" : detail.when());
        builder.set("#SelectedQuestName.Text", entry.displayName());
        StageView stage = entry.currentStage();
        boolean ongoing = detail.state() == JournalModel.State.ACTIVE || detail.state() == JournalModel.State.TRACKED;
        builder.set("#SelectedStep.Text", ongoing && entry.grouped() && stage != null
                ? stage.stepLabel() + "  |  " + stage.displayName() : "");

        Optional<ObjectiveView> next = ongoing ? nextObjective(entry) : Optional.empty();
        builder.set("#CurrentObjectiveCard.Visible", next.isPresent());
        next.ifPresent(objective -> {
            builder.set("#CurrentObjectiveText.Text", objective.displayName());
            builder.set("#CurrentObjectiveProgress.Text", objective.progressLabel());
        });
        builder.set("#SelectedQuestDescription.Text", entry.description());
        builder.set("#ObjectiveHeading.Text", "OBJECTIVES");
        builder.set("#ObjectiveSummary.Text", entry.progressLabel());
        appendObjectiveRows(builder, entry);

        builder.set("#RewardsRow.Visible", !detail.rewardText().isBlank());
        builder.set("#RewardsText.Text", detail.rewardText());
        appendTimeline(builder, detail.timeline());

        builder.set("#QuestActions.Visible", ongoing);
        if (ongoing) {
            boolean tracked = detail.state() == JournalModel.State.TRACKED;
            builder.set("#TrackButton.Text", tracked ? "STOP TRACKING" : "TRACK QUEST");
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#TrackButton",
                    EventData.of("Action", tracked ? "untrack" : "track"));
            builder.set("#AbandonButton.Text", entry.questId().equals(abandonPendingQuestId) ? "CONFIRM ABANDON" : "ABANDON");
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#AbandonButton", EventData.of("Action", "abandon"));
        }
    }

    /** A 2.0 story: whose it is, since when, and the milestones reached in it; never what comes next. */
    private void renderStory(UICommandBuilder builder, JournalSources.Story story) {
        builder.set("#DetailCategory.Text", "STORY");
        builder.set("#DetailState.Text", "IN PROGRESS");
        builder.set("#DetailWhen.Text", "Since " + JournalModel.date(story.since(), "").strip());
        builder.set("#SelectedQuestName.Text", story.name());
        builder.set("#SelectedStep.Text", story.party() ? "Shared with your party" : "Your own story");
        builder.set("#CurrentObjectiveCard.Visible", false);
        builder.set("#SelectedQuestDescription.Text", story.milestones().isEmpty()
                ? "A story you are part of. Milestones you reach in it are recorded here."
                : "A story you are part of. The milestones you have reached so far:");
        builder.set("#ObjectiveHeading.Text", "MILESTONES");
        builder.set("#ObjectiveSummary.Text", story.milestones().size() + " reached");
        int row = 0;
        for (JournalSources.Milestone milestone : story.milestones()) {
            builder.appendInline("#ObjectiveList", milestoneRow("MilestoneRow" + row++, milestone));
        }
        builder.set("#RewardsRow.Visible", false);
        appendTimeline(builder, List.of("Began on " + JournalModel.date(story.since(), "").strip()));
        builder.set("#QuestActions.Visible", false);
    }

    private static Optional<ObjectiveView> nextObjective(JournalEntry entry) {
        StageView stage = entry.currentStage();
        List<ObjectiveView> objectives = stage == null ? entry.objectives() : stage.objectives();
        return objectives.stream().filter(objective -> !objective.complete()).findFirst();
    }

    private void appendTimeline(UICommandBuilder builder, List<String> timeline) {
        builder.set("#TimelineSection.Visible", !timeline.isEmpty());
        int row = 0;
        for (String event : timeline) {
            builder.appendInline("#TimelineList", """
                    Label #TimelineRow%d {
                      Text: "%s";
                      Style: (FontSize: 12, TextColor: %s);
                      Anchor: (Bottom: 2);
                    }
                    """.formatted(row++, uiText(event), MysticQuestsTheme.TEXT_SECONDARY));
        }
    }

    // --- Settings (Redesign Bible §7.4) ---

    private void appendSettings(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        JournalSources.Settings settings = sources.settings(playerId);
        int index = 0;
        appendSetting(builder, eventBuilder, index++, "Quest tracker", "How much of your tracked quest stays on screen.",
                "tracker", List.of(
                        new Option("AUTOMATIC", "Auto", settings.tracker() == QuestHudCoordinator.Preference.AUTOMATIC),
                        new Option("COMPACT", "Compact", settings.tracker() == QuestHudCoordinator.Preference.COMPACT),
                        new Option("EXPANDED", "Expanded", settings.tracker() == QuestHudCoordinator.Preference.EXPANDED),
                        new Option("HIDDEN", "Hidden", settings.tracker() == QuestHudCoordinator.Preference.HIDDEN)));
        appendSetting(builder, eventBuilder, index++, "Story subtitles", "Subtitles for voiced story lines.",
                "subtitles", List.of(
                        new Option("on", "On", settings.subtitles()),
                        new Option("off", "Off", !settings.subtitles())));
        appendSetting(builder, eventBuilder, index++, "Voice language", settings.voiceLocale() == null
                        ? "Voice lines play in your game language. Pick another with /mquest audio voice <language>."
                        : "Voice lines play in " + settings.voiceLocale() + " when recorded in it.",
                "voice", List.of(new Option("auto", "Game language", settings.voiceLocale() == null)));
        if (settings.popupsAvailable()) {
            appendSetting(builder, eventBuilder, index, "Quest pop-ups", "Short cards when a quest is accepted, moves on or is completed.",
                    "popups", List.of(
                            new Option("on", "On", settings.popups()),
                            new Option("off", "Off", !settings.popups())));
        }
    }

    private record Option(String value, String label, boolean selected) {
    }

    private void appendSetting(UICommandBuilder builder, UIEventBuilder eventBuilder, int index, String name, String description,
                               String key, List<Option> options) {
        StringBuilder buttons = new StringBuilder();
        for (int option = 0; option < options.size(); option++) {
            Option choice = options.get(option);
            buttons.append("""
                      Button #Setting%dOption%d {
                        Anchor: (Width: 144, Height: 32, Right: 6);
                        LayoutMode: Left;
                        Padding: (Horizontal: 12);
                        Style: %s;

                        Label #Setting%dOption%dLabel {
                          Text: "%s";
                          Style: (FontSize: 13, RenderBold: true, TextColor: %s, VerticalAlignment: Center);
                        }
                      }
                    """.formatted(index, option, MysticQuestsTheme.cardButtonStyle(choice.selected()), index, option,
                    uiText(choice.label()), choice.selected() ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_SECONDARY));
        }
        builder.appendInline("#SettingsList", """
                Group #Setting%d {
                  LayoutMode: Top;
                  Anchor: (Bottom: 16);

                  Label #Setting%dName {
                    Text: "%s";
                    Style: (FontSize: 15, RenderBold: true, TextColor: %s);
                    Anchor: (Bottom: 2);
                  }

                  Label #Setting%dDescription {
                    Text: "%s";
                    Style: (FontSize: 12, TextColor: %s, Wrap: true);
                    Anchor: (Bottom: 6);
                  }

                  Group #Setting%dOptions {
                    LayoutMode: Left;
                    Anchor: (Height: 32);
                %s
                  }
                }
                """.formatted(index, index, uiText(name), MysticQuestsTheme.TEXT_PRIMARY, index, uiText(description),
                MysticQuestsTheme.TEXT_MUTED, index, buttons));
        for (int option = 0; option < options.size(); option++) {
            eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#Setting" + index + "Option" + option,
                    EventData.of("Setting", key).append("Value", options.get(option).value()));
        }
    }

    // --- Rows ---

    private String sectionHeader(String headerId, String title, int count) {
        return """
                Group #%s {
                  Anchor: (Height: 24, Top: 6);
                  LayoutMode: Left;
                  Padding: (Horizontal: 8);

                  Label #%sTitle {
                    Text: "%s";
                    Style: (FontSize: 10, RenderBold: true, LetterSpacing: 1, TextColor: %s);
                    FlexWeight: 1;
                  }

                  Label #%sCount {
                    Text: "%d";
                    Style: (FontSize: 10, RenderBold: true, TextColor: %s);
                  }
                }
                """.formatted(headerId, headerId, uiText(title), MysticQuestsTheme.TEXT_MUTED, headerId, count, MysticQuestsTheme.TEXT_MUTED);
    }

    private String railRow(String rowId, boolean selected, JournalModel.Item item) {
        String accent = switch (item.state()) {
            case TRACKED -> MysticQuestsTheme.ACCENT_GOLD;
            case STORY -> MysticQuestsTheme.NARRATIVE_PURPLE;
            case COMPLETE -> MysticQuestsTheme.ACCENT_GREEN;
            case ABANDONED -> MysticQuestsTheme.TEXT_MUTED;
            default -> selected ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TRANSPARENT;
        };
        String titleColor = selected ? MysticQuestsTheme.ACCENT_GOLD
                : item.state() == JournalModel.State.ABANDONED ? MysticQuestsTheme.TEXT_MUTED : MysticQuestsTheme.TEXT_PRIMARY;
        return """
                Button #%s {
                  Anchor: (Height: 52, Bottom: 2);
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

                    Label #%sTitle {
                      Text: "%s";
                      Style: (FontSize: 14, RenderBold: true, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 11);
                      Anchor: (Bottom: 3);
                    }

                    Label #%sSubtitle {
                      Text: "%s";
                      Style: (FontSize: 11, TextColor: %s);
                    }
                  }
                }
                """.formatted(rowId, MysticQuestsTheme.rowButtonStyle(selected), accent, rowId, uiText(item.title()), titleColor,
                rowId, uiText(item.subtitle()), MysticQuestsTheme.TEXT_MUTED);
    }

    /**
     * Objectives under their step headings, or as one flat list when the quest declares no steps.
     * Row ids stay unique across the whole list, not per step, because the page appends them all
     * into one container.
     */
    private void appendObjectiveRows(UICommandBuilder builder, JournalEntry entry) {
        int row = 0;
        for (StageView stage : entry.stages()) {
            if (entry.grouped()) {
                builder.appendInline("#ObjectiveList", stageHeader("ObjectiveStage" + stage.index(), stage));
            }
            for (ObjectiveView objective : stage.objectives()) {
                builder.appendInline("#ObjectiveList", objectiveRow("ObjectiveRow" + row, objective));
                row++;
            }
        }
    }

    private String stageHeader(String headerId, StageView stage) {
        boolean complete = stage.complete();
        String accent = complete ? MysticQuestsTheme.ACCENT_GREEN : MysticQuestsTheme.ACCENT_GOLD;
        return """
                Group #%s {
                  Anchor: (Height: 26, Top: 6, Bottom: 4);
                  LayoutMode: Left;
                  Padding: (Horizontal: 4);

                  Group {
                    Anchor: (Width: 3, Right: 8);
                    Background: %s;
                  }

                  Label #%sStep {
                    Text: "%s";
                    Style: (FontSize: 9, RenderBold: true, LetterSpacing: 1, TextColor: %s);
                    Anchor: (Width: 74, Right: 6);
                  }

                  Label #%sName {
                    Text: "%s";
                    Style: (FontSize: 13, RenderBold: true, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 10);
                    FlexWeight: 1;
                  }

                  Label #%sProgress {
                    Text: "%s";
                    Style: (FontSize: 11, RenderBold: true, TextColor: %s);
                    Anchor: (Width: 46, Left: 8);
                  }
                }
                """.formatted(
                headerId, accent, headerId, uiText(stage.stepLabel()), accent, headerId, uiText(stage.displayName()),
                MysticQuestsTheme.TEXT_PRIMARY, headerId, uiText(stage.progressLabel()), accent);
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
                rowId, state, textColor,
                rowId, uiText(objective.displayName()), textColor,
                rowId, uiText(objective.progressLabel()), progressColor);
    }

    private String milestoneRow(String rowId, JournalSources.Milestone milestone) {
        return """
                Group #%s {
                  Anchor: (Height: 32, Bottom: 4);
                  LayoutMode: Left;
                  Background: %s;
                  Padding: (Horizontal: 8);

                  Group {
                    Anchor: (Width: 3, Right: 10);
                    Background: %s;
                  }

                  Label #%sText {
                    Text: "%s";
                    Style: (FontSize: 13, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 10);
                    FlexWeight: 1;
                  }

                  Label #%sWhen {
                    Text: "%s";
                    Style: (FontSize: 11, TextColor: %s);
                    Anchor: (Width: 92, Left: 8);
                  }
                }
                """.formatted(rowId, MysticQuestsTheme.PANEL_SOFT, MysticQuestsTheme.NARRATIVE_PURPLE, rowId,
                uiText(milestone.text()), MysticQuestsTheme.TEXT_PRIMARY, rowId,
                uiText(JournalModel.date(milestone.reached(), "").strip()), MysticQuestsTheme.TEXT_MUTED);
    }

    private static String uiText(String text) {
        if (text == null) {
            return "";
        }
        return text.replace("\\", "\\\\")
                .replace("\"", "\\\"")
                .replace("\r", " ")
                .replace("\n", " ");
    }

    private static JournalModel.Kind parseKind(String raw) {
        try {
            return JournalModel.Kind.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return JournalModel.Kind.QUEST;
        }
    }

    public static final class PageEventData {
        private String kind;
        private String id;
        private String action;
        private String setting;
        private String value;

        public String kind() {
            return kind == null ? "" : kind;
        }

        public void setKind(String kind) {
            this.kind = kind;
        }

        public String id() {
            return id == null ? "" : id;
        }

        public void setId(String id) {
            this.id = id;
        }

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String setting() {
            return setting == null ? "" : setting;
        }

        public void setSetting(String setting) {
            this.setting = setting;
        }

        public String value() {
            return value == null ? "" : value;
        }

        public void setValue(String value) {
            this.value = value;
        }
    }
}
