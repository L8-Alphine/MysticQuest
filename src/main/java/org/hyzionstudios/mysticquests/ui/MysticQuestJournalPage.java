package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestResult;
import org.hyzionstudios.mysticquests.service.StageView;
import org.hyzionstudios.mysticquests.storage.ActiveQuestData;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

/**
 * The Quest Journal (Redesign Bible §7.1-7.2, §7.4). {@link JournalModel} decides what shows; this
 * page draws it in three panels — the rail of sections, the quests in the chosen section, and the
 * chosen quest's detail — and the settings, which take the place of the last two.
 *
 * <p>Every action goes back through {@link PlayerQuestService}, which checks it again; the page never
 * assumes a click worked (§10.2) and always redraws from the service's state.
 */
public final class MysticQuestJournalPage extends MysticQuestsPage<MysticQuestJournalPage.PageEventData> {
    static final String DOCUMENT = "mysticquests/Pages/JournalPage.ui";
    static final String RAIL_ROW = "mysticquests/Rows/JournalRailRow.ui";
    static final String QUEST_CARD = "mysticquests/Rows/JournalQuestCard.ui";
    static final String OBJECTIVE_ROW = "mysticquests/Rows/JournalObjectiveRow.ui";
    static final String STEP_ROW = "mysticquests/Rows/JournalStepRow.ui";
    static final String TIMELINE_ROW = "mysticquests/Rows/TimelineRow.ui";

    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action).add()
            .append(new KeyedCodec<>("Kind", Codec.STRING), PageEventData::setKind, PageEventData::kind).add()
            .append(new KeyedCodec<>("Id", Codec.STRING), PageEventData::setId, PageEventData::id).add()
            .append(new KeyedCodec<>("Setting", Codec.STRING), PageEventData::setSetting, PageEventData::setting).add()
            .append(new KeyedCodec<>("Value", Codec.STRING), PageEventData::setValue, PageEventData::value).add()
            .build();

    private final PlayerQuestService questService;
    private final JournalSources sources;
    private final UUID playerId;

    private boolean settingsOpen;
    @Nullable
    private String selectedSection;
    @Nullable
    private JournalModel.Kind selectedKind;
    @Nullable
    private String selectedId;
    /** Quest awaiting a second Abandon press; cleared by any other action. */
    @Nullable
    private String abandonPendingQuestId;
    /** What the last action did, shown beside the action buttons until the next action. */
    @Nullable
    private Message actionNote;

    public MysticQuestJournalPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService, JournalSources sources) {
        super(playerRef, EVENT_CODEC);
        this.playerId = playerId;
        this.questService = questService;
        this.sources = sources;
    }

    @Override
    protected String document() {
        return DOCUMENT;
    }

    // --- Events ---

    @Override
    protected void handle(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        String action = data.action();
        if (!action.equals("abandon")) {
            abandonPendingQuestId = null;
        }
        actionNote = null;
        switch (action) {
            case "close" -> closePage();
            case "section" -> {
                settingsOpen = false;
                selectedSection = data.id();
                selectedKind = null;
                selectedId = null;
            }
            case "select" -> {
                settingsOpen = false;
                selectedKind = parseKind(data.kind());
                selectedId = data.id();
            }
            case "settings" -> settingsOpen = true;
            case "setting" -> applySetting(data.setting(), data.value());
            case "defaults" -> {
                sources.setTracker(playerId, QuestHudCoordinator.Preference.AUTOMATIC);
                sources.setSubtitles(playerId, true);
                sources.followGameLanguage(playerId);
                sources.setPopups(playerId, true);
            }
            case "track", "untrack", "abandon" -> questAction(action);
            default -> {
                // An action from an older page; the refresh shows the current state.
            }
        }
    }

    @Override
    protected void onFailure(RuntimeException failure) {
        actionNote = UiText.of("That did not work. Try again, or tell staff if it keeps happening.", UiText.RED);
    }

    private void questAction(String action) {
        if (selectedKind != JournalModel.Kind.QUEST || selectedId == null) {
            return;
        }
        QuestResult result = switch (action) {
            case "track" -> questService.trackQuest(playerId, selectedId);
            case "untrack" -> questService.untrackQuest(playerId);
            default -> {
                if (!selectedId.equals(abandonPendingQuestId)) {
                    abandonPendingQuestId = selectedId;
                    actionNote = UiText.of("Press Confirm to abandon. Your progress on this quest is lost.", UiText.RED);
                    yield null;
                }
                abandonPendingQuestId = null;
                QuestResult abandoned = questService.abandonQuest(playerId, selectedId);
                if (abandoned.success()) {
                    selectedId = null;
                    selectedKind = null;
                }
                yield abandoned;
            }
        };
        if (result != null) {
            actionNote = UiText.status(result.message(), result.success());
        }
    }

    private void applySetting(String setting, String value) {
        switch (setting) {
            case "tracker" -> {
                try {
                    sources.setTracker(playerId, QuestHudCoordinator.Preference.valueOf(value));
                } catch (IllegalArgumentException unknown) {
                    // A value from an older page; the refresh shows the current setting.
                }
            }
            case "subtitles" -> sources.setSubtitles(playerId, value.equals("on"));
            case "voice" -> sources.followGameLanguage(playerId);
            case "popups" -> sources.setPopups(playerId, value.equals("on"));
            default -> {
                // Unknown setting from an older page.
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

    @Override
    protected void render(UICommandBuilder commands, UIEventBuilder events) {
        JournalModel model = model();
        PlayerQuestData data = questService.data(playerId);
        commands.set("#HeaderSummary.Text", data.activeQuests().size() + " active   "
                + model.storyCount() + " stories   " + data.completedQuests().size() + " completed");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));

        // Resolve the selection: the chosen entry if it is still there, else the first entry of the
        // chosen section, else the model's default (the tracked quest).
        Optional<JournalModel.Section> section = model.section(selectedSection);
        Optional<JournalModel.Item> item = model.select(selectedKind, selectedId);
        if (section.isPresent() && (item.isEmpty() || !model.sectionOf(item.get())
                .map(found -> found.title().equals(section.get().title())).orElse(false))) {
            item = section.get().items().stream().findFirst();
        }
        item.ifPresent(found -> {
            selectedKind = found.kind();
            selectedId = found.id();
        });
        JournalModel.Section activeSection = item.flatMap(model::sectionOf).or(() -> section)
                .orElse(model.sections().isEmpty() ? null : model.sections().get(0));
        selectedSection = activeSection == null ? null : activeSection.title();

        renderRail(commands, events, model, activeSection);
        commands.set("#SettingsButton.Style", settingsOpen ? UiStyles.NAV_SELECTED : UiStyles.NAV);
        events.addEventBinding(CustomUIEventBindingType.Activating, "#SettingsButton", EventData.of("Action", "settings"));

        commands.set("#SettingsPanel.Visible", settingsOpen);
        commands.set("#ListPanel.Visible", !settingsOpen);
        commands.set("#DetailPanel.Visible", !settingsOpen);
        if (settingsOpen) {
            renderSettings(commands, events);
            return;
        }
        renderList(commands, events, activeSection, item.orElse(null));
        if (item.isEmpty()) {
            commands.set("#EmptyState.Visible", true);
            commands.set("#QuestDetails.Visible", false);
            commands.set("#EmptyTitle.Text", "Your journal is empty");
            commands.set("#EmptyBody.Text", "Find a quest board with /quest, or speak with a quest giver. Quests you take on appear here.");
            return;
        }
        commands.set("#EmptyState.Visible", false);
        commands.set("#QuestDetails.Visible", true);
        if (item.get().kind() == JournalModel.Kind.STORY) {
            model.story(item.get().id()).ifPresent(story -> renderStory(commands, story));
        } else {
            model.quest(item.get().id()).ifPresent(detail -> renderQuest(commands, events, detail));
        }
    }

    private void renderRail(UICommandBuilder commands, UIEventBuilder events, JournalModel model,
                            @Nullable JournalModel.Section active) {
        commands.clear("#RailList");
        int index = 0;
        for (JournalModel.Section section : model.sections()) {
            boolean selected = !settingsOpen && section == active;
            String row = "#RailList[" + index + "]";
            commands.append("#RailList", RAIL_ROW);
            commands.set(row + ".Style", selected ? UiStyles.NAV_ROW_SELECTED : UiStyles.NAV_ROW);
            commands.set(row + " #Name.TextSpans", selected
                    ? UiText.bold(railName(section.title()), UiText.TEXT)
                    : UiText.of(railName(section.title()), UiText.MUTED));
            commands.set(row + " #Count.TextSpans", UiText.of(Integer.toString(section.items().size()),
                    selected ? UiText.PURPLE_TEXT : UiText.DIM));
            events.addEventBinding(CustomUIEventBindingType.Activating, row,
                    new EventData().append("Action", "section").append("Id", section.title()));
            index++;
        }
    }

    /** "STORY QUESTS" reads as "Story quests" in the rail; the list heading keeps the capitals. */
    private static String railName(String title) {
        if (title.isEmpty()) {
            return title;
        }
        String lower = title.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private void renderList(UICommandBuilder commands, UIEventBuilder events, @Nullable JournalModel.Section section,
                            @Nullable JournalModel.Item selected) {
        commands.clear("#QuestList");
        commands.set("#ListTitle.Text", section == null ? "Quests" : section.title());
        if (section == null) {
            return;
        }
        int index = 0;
        for (JournalModel.Item item : section.items()) {
            boolean isSelected = selected != null && item.kind() == selected.kind() && item.id().equals(selected.id());
            String card = "#QuestList[" + index + "]";
            commands.append("#QuestList", QUEST_CARD);
            commands.set(card + ".Style", isSelected ? UiStyles.CARD_SELECTED : UiStyles.CARD);
            commands.set(card + " #Title.TextSpans", UiText.bold(UiText.oneLine(item.title()), UiText.TEXT));
            commands.set(card + " #Context.TextSpans", UiText.muted(cardContext(item)));
            commands.set(card + " #Status.TextSpans", cardStatus(item));
            events.addEventBinding(CustomUIEventBindingType.Activating, card,
                    new EventData().append("Action", "select").append("Kind", item.kind().name()).append("Id", item.id()));
            index++;
        }
    }

    private String cardContext(JournalModel.Item item) {
        if (item.kind() == JournalModel.Kind.STORY) {
            return item.subtitle();
        }
        String category = questService.definition(item.id()).map(QuestDefinition::category).orElse("");
        String label = QuestCategories.section(category);
        return label.charAt(0) + label.substring(1).toLowerCase(java.util.Locale.ROOT);
    }

    private static Message cardStatus(JournalModel.Item item) {
        return switch (item.state()) {
            case TRACKED -> UiText.pair("Tracked  ", UiText.GOLD, item.subtitle(), UiText.GOLD);
            case COMPLETE -> UiText.of(item.subtitle(), UiText.GREEN);
            case ABANDONED -> UiText.of(item.subtitle(), UiText.DIM);
            case STORY -> UiText.of("In progress", UiText.PURPLE_TEXT);
            default -> UiText.of(item.subtitle(), UiText.GOLD);
        };
    }

    private void renderQuest(UICommandBuilder commands, UIEventBuilder events, JournalModel.QuestDetail detail) {
        JournalEntry entry = detail.entry();
        boolean ongoing = detail.state() == JournalModel.State.ACTIVE || detail.state() == JournalModel.State.TRACKED;
        commands.set("#DetailCategory.Text", QuestCategories.badge(detail.category()));
        commands.set("#DetailCategory.Style", UiStyles.categoryTone(detail.category()).style());
        commands.set("#DetailState.Text", switch (detail.state()) {
            case TRACKED -> "Tracked";
            case COMPLETE -> "Complete";
            case ABANDONED -> "Abandoned";
            default -> "Active";
        });
        commands.set("#DetailState.Style", switch (detail.state()) {
            case TRACKED -> UiStyles.Tone.GOLD_OUTLINE.style();
            case COMPLETE -> UiStyles.Tone.GREEN.style();
            default -> UiStyles.Tone.NEUTRAL.style();
        });
        commands.set("#DetailWhen.Text", detail.when() == null ? "" : detail.when());
        commands.set("#DetailTitle.Text", UiText.oneLine(entry.displayName()));
        StageView stage = entry.currentStage();
        commands.set("#DetailContext.Text", ongoing && entry.grouped() && stage != null
                ? stepLine(stage) : sentenceCase(QuestCategories.section(detail.category())));

        // The current objective, with both a meter and the number (§4.2).
        Optional<ObjectiveView> next = ongoing ? nextObjective(entry) : Optional.empty();
        commands.set("#ObjectiveBlock.Visible", next.isPresent());
        next.ifPresent(objective -> {
            boolean counted = !objective.progressLabel().isEmpty();
            commands.set("#ObjectiveText.Text", UiText.oneLine(objective.displayName()));
            commands.set("#ObjectiveMeterRow.Visible", counted);
            commands.set("#ObjectiveMeter.Value", counted ? (float) objective.current() / objective.target() : 0f);
            commands.set("#ObjectiveProgress.Text", objective.progressLabel());
        });

        commands.set("#RecapBlock.Visible", !entry.description().isBlank());
        commands.set("#RecapText.Text", entry.description());

        commands.set("#ObjectivesBlock.Visible", true);
        commands.set("#ObjectivesCaption.Text", "Objectives");
        commands.set("#ObjectivesSummary.Text", entry.progressLabel());
        renderObjectives(commands, entry);

        commands.set("#RewardsBlock.Visible", !detail.rewardText().isBlank());
        commands.set("#RewardsText.Text", detail.rewardText());
        renderTimeline(commands, detail.timeline());

        commands.set("#Actions.Visible", ongoing || actionNote != null);
        commands.set("#TrackButton.Visible", ongoing);
        commands.set("#AbandonButton.Visible", ongoing);
        if (ongoing) {
            boolean tracked = detail.state() == JournalModel.State.TRACKED;
            commands.set("#TrackButton.Text", tracked ? "Stop tracking" : "Track quest");
            commands.set("#TrackButton.Style", tracked ? UiStyles.BUTTON_SECONDARY : UiStyles.BUTTON_PRIMARY);
            events.addEventBinding(CustomUIEventBindingType.Activating, "#TrackButton",
                    EventData.of("Action", tracked ? "untrack" : "track"));
            commands.set("#AbandonButton.Text", entry.questId().equals(abandonPendingQuestId) ? "Confirm abandon" : "Abandon");
            events.addEventBinding(CustomUIEventBindingType.Activating, "#AbandonButton", EventData.of("Action", "abandon"));
        }
        commands.set("#ActionNote.TextSpans", actionNote == null ? UiText.muted("") : actionNote);
    }

    /** A 2.0 story: whose it is, since when, and the milestones reached in it; never what comes next. */
    private void renderStory(UICommandBuilder commands, JournalSources.Story story) {
        commands.set("#DetailCategory.Text", "Story");
        commands.set("#DetailCategory.Style", UiStyles.Tone.PURPLE.style());
        commands.set("#DetailState.Text", "In progress");
        commands.set("#DetailState.Style", UiStyles.Tone.GOLD_OUTLINE.style());
        commands.set("#DetailWhen.Text", "Since " + JournalModel.date(story.since(), "").strip());
        commands.set("#DetailTitle.Text", UiText.oneLine(story.name()));
        commands.set("#DetailContext.Text", story.party() ? "Shared with your party" : "Your own story");
        commands.set("#ObjectiveBlock.Visible", false);
        commands.set("#RecapBlock.Visible", true);
        commands.set("#RecapText.Text", story.milestones().isEmpty()
                ? "A story you are part of. The milestones you reach in it are recorded here."
                : "A story you are part of. The milestones you have reached so far are below.");

        commands.set("#ObjectivesBlock.Visible", !story.milestones().isEmpty());
        commands.set("#ObjectivesCaption.Text", "Milestones");
        commands.set("#ObjectivesSummary.Text", story.milestones().size() + " reached");
        commands.clear("#ObjectiveList");
        int index = 0;
        for (JournalSources.Milestone milestone : story.milestones()) {
            String row = "#ObjectiveList[" + index++ + "]";
            commands.append("#ObjectiveList", TIMELINE_ROW);
            commands.set(row + " #When.Text", JournalModel.date(milestone.reached(), "").strip());
            commands.set(row + " #Text.Text", UiText.oneLine(milestone.text()));
        }
        commands.set("#RewardsBlock.Visible", false);
        renderTimeline(commands, List.of(new JournalModel.Moment(JournalModel.date(story.since(), "").strip(), "Began")));
        commands.set("#Actions.Visible", false);
    }

    private void renderObjectives(UICommandBuilder commands, JournalEntry entry) {
        commands.clear("#ObjectiveList");
        Optional<ObjectiveView> next = nextObjective(entry);
        int index = 0;
        for (StageView stage : entry.stages()) {
            if (entry.grouped()) {
                String row = "#ObjectiveList[" + index++ + "]";
                commands.append("#ObjectiveList", STEP_ROW);
                String colour = stage.complete() ? UiText.GREEN : UiText.GOLD;
                commands.set(row + " #Step.TextSpans", UiText.of(stage.stepLabel(), colour));
                commands.set(row + " #Name.Text", UiText.oneLine(stage.displayName()));
                commands.set(row + " #Progress.TextSpans", UiText.of(stage.progressLabel(), colour));
            }
            for (ObjectiveView objective : stage.objectives()) {
                String row = "#ObjectiveList[" + index++ + "]";
                commands.append("#ObjectiveList", OBJECTIVE_ROW);
                boolean current = next.map(found -> found == objective).orElse(false);
                Message mark = objective.complete() ? UiText.of("Done", UiText.GREEN)
                        : current ? UiText.of("Now", UiText.GOLD) : UiText.of("To do", UiText.DIM);
                commands.set(row + " #Mark.TextSpans", mark);
                commands.set(row + " #Name.TextSpans", UiText.of(UiText.oneLine(objective.displayName()),
                        objective.complete() ? UiText.MUTED : UiText.TEXT));
                commands.set(row + " #Progress.TextSpans", UiText.of(objective.progressLabel(),
                        objective.complete() ? UiText.GREEN : current ? UiText.GOLD : UiText.MUTED));
            }
        }
    }

    private void renderTimeline(UICommandBuilder commands, List<JournalModel.Moment> timeline) {
        commands.clear("#TimelineList");
        commands.set("#TimelineBlock.Visible", !timeline.isEmpty());
        int index = 0;
        for (JournalModel.Moment moment : timeline) {
            String row = "#TimelineList[" + index++ + "]";
            commands.append("#TimelineList", TIMELINE_ROW);
            commands.set(row + " #When.Text", moment.when());
            commands.set(row + " #Text.Text", moment.what());
        }
    }

    // --- Settings (Redesign Bible §7.4) ---

    private void renderSettings(UICommandBuilder commands, UIEventBuilder events) {
        JournalSources.Settings settings = sources.settings(playerId);
        option(commands, events, "#DensityAuto", settings.tracker() == QuestHudCoordinator.Preference.AUTOMATIC, "tracker", "AUTOMATIC");
        option(commands, events, "#DensityExpanded", settings.tracker() == QuestHudCoordinator.Preference.EXPANDED, "tracker", "EXPANDED");
        option(commands, events, "#DensityCompact", settings.tracker() == QuestHudCoordinator.Preference.COMPACT, "tracker", "COMPACT");
        option(commands, events, "#DensityHidden", settings.tracker() == QuestHudCoordinator.Preference.HIDDEN, "tracker", "HIDDEN");
        option(commands, events, "#SubtitlesOn", settings.subtitles(), "subtitles", "on");
        option(commands, events, "#SubtitlesOff", !settings.subtitles(), "subtitles", "off");
        commands.set("#VoiceLabel.Text", settings.voiceLocale() == null
                ? "Voice lines play in your game language."
                : "Voice lines play in " + settings.voiceLocale() + " when recorded in it.");
        option(commands, events, "#VoiceAuto", settings.voiceLocale() == null, "voice", "auto");

        commands.set("#PopupRow.Visible", settings.popupsAvailable());
        commands.set("#MotionHint.Text", settings.popupsAvailable()
                ? "Quest pop-ups are the only motion quests add to your screen. Turn them off for reduced motion; the tracker still updates."
                : "This server does not show quest pop-ups, so quests add no motion to your screen.");
        if (settings.popupsAvailable()) {
            option(commands, events, "#PopupsOn", settings.popups(), "popups", "on");
            option(commands, events, "#PopupsOff", !settings.popups(), "popups", "off");
        }
        events.addEventBinding(CustomUIEventBindingType.Activating, "#RestoreDefaults", EventData.of("Action", "defaults"));
    }

    private static void option(UICommandBuilder commands, UIEventBuilder events, String selector, boolean selected,
                               String setting, String value) {
        commands.set(selector + ".Style", selected ? UiStyles.BUTTON_SELECTED : UiStyles.BUTTON_SECONDARY);
        events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                new EventData().append("Action", "setting").append("Setting", setting).append("Value", value));
    }

    // --- Helpers ---

    private static Optional<ObjectiveView> nextObjective(JournalEntry entry) {
        StageView stage = entry.currentStage();
        List<ObjectiveView> objectives = stage == null ? entry.objectives() : stage.objectives();
        return objectives.stream().filter(objective -> !objective.complete()).findFirst();
    }

    private static String stepLine(StageView stage) {
        return sentenceCase(stage.stepLabel()) + "  -  " + stage.displayName();
    }

    private static String sentenceCase(String text) {
        if (text == null || text.isEmpty()) {
            return "";
        }
        String lower = text.toLowerCase(java.util.Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    private static JournalModel.Kind parseKind(String raw) {
        try {
            return JournalModel.Kind.valueOf(raw);
        } catch (IllegalArgumentException unknown) {
            return JournalModel.Kind.QUEST;
        }
    }

    public static final class PageEventData {
        private String action;
        private String kind;
        private String id;
        private String setting;
        private String value;

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }

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
