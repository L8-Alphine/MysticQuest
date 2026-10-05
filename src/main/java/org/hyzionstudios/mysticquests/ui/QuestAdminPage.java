package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Part;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.QuestLine;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.SessionLine;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Snapshot;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.StateLine;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.ObjectiveDraft;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.QuestDraft;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.SaveResult;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.SourceSaveResult;

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
import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.EnumSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

/**
 * The in-game quest admin (Redesign Bible §9.1): a player inspector with audited corrections, and
 * the quick quest builder and package script editor.
 *
 * <p>Every change to a player goes through {@link PlayerStateAdmin}, the same service as
 * {@code /mquest player} and the web Studio: it needs a reason, keeps the runtime's own checks, and
 * is audited. Destructive actions (reset, abandon, restart, rewind, clear) take a second press or a
 * confirmation dialog. The permission is checked again on every change, not only when the page
 * opened, because events arrive from the client.
 *
 * <p>Text fields are read through {@code "@Key": "#Field.Value"} bindings — the client fills in the
 * field's current value — and written by the server only when the page is built or a form is
 * loaded, because after that the client owns them.
 */
public final class QuestAdminPage extends MysticQuestsPage<QuestAdminPage.PageEventData> {
    static final String DOCUMENT = "mysticquests/Pages/QuestAdminPage.ui";
    static final String PLAYER_ROW = "mysticquests/Rows/AdminPlayerRow.ui";
    static final String QUEST_ROW = "mysticquests/Rows/AdminQuestRow.ui";
    static final String VALUE_ROW = "mysticquests/Rows/AdminValueRow.ui";
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy HH:mm", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    private enum View { PLAYERS, BUILDER, SCRIPTS }

    private enum Tab { QUESTS, STATE, STORY }

    private static final BuilderCodec<PageEventData> EVENT_CODEC = eventCodec();

    private final MysticQuestsRuntime runtime;
    private final UUID adminId;
    private View view = View.PLAYERS;
    private Tab tab = Tab.QUESTS;
    @Nullable
    private UUID selected;
    private boolean clearOpen;
    private final Set<Part> clearParts = EnumSet.noneOf(Part.class);
    /** The destructive action waiting for its second press, such as {@code quest-reset|kingdom:intro}. */
    @Nullable
    private String pendingConfirm;
    @Nullable
    private Message status;

    /** Set when the builder or script fields must be written by the server on the next render. */
    private boolean writeFields = true;
    @Nullable
    private QuestDraft draft;
    private String scriptPackage = "custom";
    private String scriptFile = "scripts.yml";
    private String scriptSource = "# Named scripting elements\nconditions: {}\nactions: {}\nobjectives: {}\nquests: []\n";

    public QuestAdminPage(PlayerRef playerRef, MysticQuestsRuntime runtime) {
        super(playerRef, EVENT_CODEC);
        this.runtime = runtime;
        this.adminId = playerRef.getUuid();
        this.selected = adminId;
        this.clearParts.addAll(Part.progress());
    }

    @Override
    protected String document() {
        return DOCUMENT;
    }

    // --- Events ---

    @Override
    protected void handle(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        String action = data.action();
        if (!isConfirmable(action)) {
            // Anything else withdraws a confirmation that was waiting for its second press.
            pendingConfirm = null;
        }
        status = null;
        switch (action) {
            case "close" -> closePage();
            case "nav" -> {
                view = parse(View.class, data.id(), view);
                writeFields = true;
            }
            case "tab" -> tab = parse(Tab.class, data.id(), tab);
            case "find" -> find(data.find());
            case "select" -> {
                try {
                    selected = UUID.fromString(data.id());
                    tab = Tab.QUESTS;
                } catch (IllegalArgumentException invalid) {
                    status = UiText.of("That player is no longer listed.", UiText.RED);
                }
            }
            case "refresh" -> status = UiText.muted("Showing the player's current state.");
            case "clear-open" -> {
                if (allowed()) {
                    clearOpen = true;
                    clearParts.clear();
                    clearParts.addAll(Part.progress());
                }
            }
            case "clear-part" -> {
                Part part = Part.parse(data.id());
                if (part != null && !clearParts.remove(part)) {
                    clearParts.add(part);
                }
            }
            case "clear-cancel" -> clearOpen = false;
            case "clear-confirm" -> playerChange(admin -> {
                Outcome outcome = admin.clear(actor(), selected, clearParts, data.clearReason());
                if (outcome.ok()) {
                    clearOpen = false;
                }
                return outcome;
            });
            case "quest-complete" -> playerChange(admin -> admin.completeQuest(actor(), selected, data.id(), data.reason()));
            case "quest-track" -> playerChange(admin -> admin.trackQuest(actor(), selected, data.id(), data.reason()));
            case "quest-allow" -> playerChange(admin -> admin.allowAgain(actor(), selected, data.id(), data.reason()));
            case "quest-abandon" -> confirmed(action, data.id(),
                    admin -> admin.abandonQuest(actor(), selected, data.id(), data.reason()));
            case "quest-reset" -> confirmed(action, data.id(),
                    admin -> admin.resetQuest(actor(), selected, data.id(), data.reason()));
            case "grant" -> playerChange(admin -> admin.startQuest(actor(), selected, data.quest().trim(), data.reason()));
            case "objective" -> playerChange(admin -> {
                int value;
                try {
                    value = Integer.parseInt(data.value().trim());
                } catch (NumberFormatException notNumber) {
                    return new Outcome(false, "The objective value must be a whole number.");
                }
                return admin.setObjective(actor(), selected, data.quest().trim(), data.objective().trim(), value, data.reason());
            });
            case "tag-add" -> playerChange(admin -> admin.addTag(actor(), selected, data.tag(), data.reason()));
            case "tag-remove" -> playerChange(admin -> admin.removeTag(actor(), selected, data.id(), data.reason()));
            case "var-set" -> playerChange(admin -> admin.setVariable(actor(), selected, data.varKey(), data.varValue(), data.reason()));
            case "var-remove" -> playerChange(admin -> admin.removeVariable(actor(), selected, data.id(), data.reason()));
            case "story-var-set" -> playerChange(admin -> admin.setStoryVariable(actor(), selected, data.storyVar(), data.storyValue(), data.reason()));
            case "story-var-remove" -> playerChange(admin -> admin.removeStoryVariable(actor(), selected, data.kind(), data.id(), data.reason()));
            case "story-tag-add" -> playerChange(admin -> admin.addStoryTag(actor(), selected, data.storyTag(), data.reason()));
            case "story-tag-remove" -> playerChange(admin -> admin.removeStoryTag(actor(), selected, data.kind(), data.id(), data.reason()));
            case "story-restart" -> confirmed(action, data.id(),
                    admin -> admin.restartStory(actor(), selected, data.id(), data.reason()));
            case "rewind" -> confirmed(action, data.checkpoint(),
                    admin -> admin.rewind(actor(), selected, data.checkpoint().trim(), data.reason()));
            case "builder-new" -> {
                draft = null;
                writeFields = true;
                status = UiText.muted("Cleared the form.");
            }
            case "builder-load" -> loadDraft(data);
            case "builder-publish" -> publishDraft(data);
            case "scripts-load" -> loadScript(data);
            case "scripts-publish" -> publishScript(data);
            default -> {
                // An action from an older page; the refresh shows the current state.
            }
        }
    }

    @Override
    protected void onFailure(RuntimeException failure) {
        status = UiText.of("That failed: " + failure.getMessage(), UiText.RED);
    }

    private static boolean isConfirmable(String action) {
        return action.equals("quest-abandon") || action.equals("quest-reset") || action.equals("story-restart") || action.equals("rewind");
    }

    /** A destructive change: the first press arms it, the second runs it. */
    private void confirmed(String action, String target, java.util.function.Function<PlayerStateAdmin, Outcome> change) {
        String key = action + "|" + target;
        if (!key.equals(pendingConfirm)) {
            pendingConfirm = key;
            status = UiText.of("Press again to confirm. This cannot be undone.", UiText.GOLD);
            return;
        }
        pendingConfirm = null;
        playerChange(change);
    }

    private void playerChange(java.util.function.Function<PlayerStateAdmin, Outcome> change) {
        if (selected == null) {
            status = UiText.of("Choose a player first.", UiText.RED);
            return;
        }
        if (!allowed()) {
            return;
        }
        Outcome outcome = change.apply(runtime.playerAdmin());
        status = UiText.status(outcome.message(), outcome.ok());
    }

    private boolean allowed() {
        if (playerRef.hasPermission("mysticquests.admin") || playerRef.hasPermission(PlayerStateAdmin.PERMISSION)) {
            return true;
        }
        status = UiText.of("You need " + PlayerStateAdmin.PERMISSION + " to change a player.", UiText.RED);
        return false;
    }

    private String actor() {
        return adminId.toString();
    }

    private void find(String token) {
        String query = token == null ? "" : token.trim();
        if (query.isEmpty()) {
            status = UiText.of("Type a player name or UUID.", UiText.RED);
            return;
        }
        if (query.equalsIgnoreCase("self")) {
            selected = adminId;
            return;
        }
        Optional<UUID> online = runtime.resolveOnlinePlayer(query);
        if (online.isPresent()) {
            selected = online.get();
            tab = Tab.QUESTS;
            return;
        }
        try {
            selected = UUID.fromString(query);
            tab = Tab.QUESTS;
            status = UiText.muted("Showing a player by UUID; offline players' state is read from storage.");
        } catch (IllegalArgumentException invalid) {
            status = UiText.of("No online player called " + query + ". Offline players are found by UUID.", UiText.RED);
        }
    }

    // --- Builder and scripts ---

    private void loadDraft(PageEventData data) {
        try {
            draft = runtime.authoringService().load(data.packageId(), data.questId());
            writeFields = true;
            status = UiText.status("Loaded " + draft.packageId() + ":" + draft.questId() + ".", true);
        } catch (IOException failure) {
            status = UiText.status(failure.getMessage(), false);
        }
    }

    private void publishDraft(PageEventData data) {
        List<ObjectiveDraft> objectives = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            objectives.add(new ObjectiveDraft(data.objectiveId(index), data.objectiveType(index), data.objectiveTitle(index),
                    data.objectiveTarget(index), parseInt(data.objectiveAmount(index), 1)));
        }
        QuestDraft candidate = new QuestDraft(data.packageId(), data.questId(), data.title(), data.description(),
                data.startOnJoin(), data.cooldown(), objectives, json(data.extraObjectives()), json(data.startConditions()),
                json(data.startEvents()), json(data.completeEvents()), json(data.rewards()), json(data.reacceptConditions()));
        try {
            SaveResult result = runtime.authoringService().save(candidate);
            draft = candidate;
            status = UiText.status("Published " + result.questId() + "; " + result.loadedQuestCount() + " quests loaded.", true);
        } catch (IOException failure) {
            status = UiText.status(failure.getMessage(), false);
        }
    }

    private void loadScript(PageEventData data) {
        scriptPackage = blankDefault(data.scriptPackage(), "custom");
        scriptFile = blankDefault(data.scriptFile(), "scripts.yml");
        try {
            scriptSource = runtime.authoringService().loadSource(scriptPackage, scriptFile);
            writeFields = true;
            status = UiText.status("Loaded " + scriptPackage + "/" + scriptFile + ".", true);
        } catch (IOException failure) {
            status = UiText.status(failure.getMessage(), false);
        }
    }

    private void publishScript(PageEventData data) {
        scriptPackage = blankDefault(data.scriptPackage(), "custom");
        scriptFile = blankDefault(data.scriptFile(), "scripts.yml");
        scriptSource = data.scriptSource();
        try {
            SourceSaveResult result = runtime.authoringService().saveSource(scriptPackage, scriptFile, scriptSource);
            status = UiText.status("Published " + result.file().getFileName() + "; " + result.loadedQuestCount() + " quests loaded.", true);
        } catch (IOException failure) {
            status = UiText.status(failure.getMessage(), false);
        }
    }

    // --- Rendering ---

    @Override
    protected void render(UICommandBuilder commands, UIEventBuilder events) {
        commands.set("#StatusText.TextSpans", status == null ? UiText.muted("") : status);
        bind(events, "#CloseButton", EventData.of("Action", "close"));
        nav(commands, events, "#NavPlayers", View.PLAYERS);
        nav(commands, events, "#NavBuilder", View.BUILDER);
        nav(commands, events, "#NavScripts", View.SCRIPTS);
        commands.set("#PlayersView.Visible", view == View.PLAYERS);
        commands.set("#BuilderView.Visible", view == View.BUILDER);
        commands.set("#ScriptsView.Visible", view == View.SCRIPTS);
        commands.set("#ClearDialog.Visible", clearOpen && view == View.PLAYERS);
        switch (view) {
            case PLAYERS -> renderPlayers(commands, events);
            case BUILDER -> renderBuilder(commands, events);
            case SCRIPTS -> renderScripts(commands, events);
        }
        writeFields = false;
    }

    private void nav(UICommandBuilder commands, UIEventBuilder events, String selector, View target) {
        commands.set(selector + ".Style", view == target ? UiStyles.NAV_SELECTED : UiStyles.NAV);
        bind(events, selector, new EventData().append("Action", "nav").append("Id", target.name()));
    }

    private void renderPlayers(UICommandBuilder commands, UIEventBuilder events) {
        bind(events, "#FindButton", new EventData().append("Action", "find").append("@Find", "#FindField.Value"));
        renderPlayerList(commands, events);
        if (selected == null) {
            commands.set("#InspectorEmpty.Visible", true);
            commands.set("#InspectorBody.Visible", false);
            return;
        }
        commands.set("#InspectorEmpty.Visible", false);
        commands.set("#InspectorBody.Visible", true);
        Snapshot snapshot = runtime.playerAdmin().snapshot(selected);
        commands.set("#PlayerName.Text", UiText.oneLine(snapshot.name()));
        commands.set("#PlayerSub.TextSpans", Message.empty()
                .insert(Message.raw(snapshot.online() ? "Online" : "Offline").color(snapshot.online() ? UiText.GREEN : UiText.MUTED))
                .insert(Message.raw("    " + snapshot.player()).color(UiText.DIM)));
        bind(events, "#RefreshButton", EventData.of("Action", "refresh"));
        bind(events, "#ClearButton", EventData.of("Action", "clear-open"));

        tab(commands, events, "#TabQuests", Tab.QUESTS);
        tab(commands, events, "#TabState", Tab.STATE);
        tab(commands, events, "#TabStory", Tab.STORY);
        commands.set("#QuestsSection.Visible", tab == Tab.QUESTS);
        commands.set("#StateSection.Visible", tab == Tab.STATE);
        commands.set("#StorySection.Visible", tab == Tab.STORY);
        switch (tab) {
            case QUESTS -> renderQuests(commands, events, snapshot);
            case STATE -> renderState(commands, events, snapshot);
            case STORY -> renderStory(commands, events, snapshot);
        }
        renderClearDialog(commands, events, snapshot);
    }

    private void tab(UICommandBuilder commands, UIEventBuilder events, String selector, Tab target) {
        commands.set(selector + ".Style", tab == target ? UiStyles.CHIP_SELECTED : UiStyles.CHIP);
        bind(events, selector, new EventData().append("Action", "tab").append("Id", target.name()));
    }

    private void renderPlayerList(UICommandBuilder commands, UIEventBuilder events) {
        commands.clear("#PlayerList");
        List<Map.Entry<UUID, String>> players = new ArrayList<>(runtime.onlinePlayers().entrySet());
        players.sort(Comparator.comparing(Map.Entry::getValue, String.CASE_INSENSITIVE_ORDER));
        if (selected != null && players.stream().noneMatch(entry -> entry.getKey().equals(selected))) {
            players.add(0, Map.entry(selected, "Offline player"));
        }
        int index = 0;
        for (Map.Entry<UUID, String> player : players) {
            String row = "#PlayerList[" + index++ + "]";
            commands.append("#PlayerList", PLAYER_ROW);
            commands.set(row + ".Style", player.getKey().equals(selected) ? UiStyles.CARD_SELECTED : UiStyles.CARD);
            commands.set(row + " #Name.Text", UiText.oneLine(player.getValue()));
            int active = runtime.questService().data(player.getKey()).activeQuests().size();
            commands.set(row + " #Sub.Text", (active == 1 ? "1 active quest" : active + " active quests")
                    + (player.getKey().equals(adminId) ? "  -  you" : ""));
            bind(events, row, new EventData().append("Action", "select").append("Id", player.getKey().toString()));
        }
    }

    private void renderQuests(UICommandBuilder commands, UIEventBuilder events, Snapshot snapshot) {
        commands.clear("#QuestRows");
        commands.set("#QuestsEmpty.Visible", snapshot.quests().isEmpty());
        int index = 0;
        for (QuestLine quest : snapshot.quests()) {
            String row = "#QuestRows[" + index++ + "]";
            commands.append("#QuestRows", QUEST_ROW);
            commands.set(row + " #Name.Text", UiText.oneLine(quest.name()));
            commands.set(row + " #Sub.Text", quest.questId() + "    " + UiText.oneLine(quest.detail())
                    + (quest.at() == null ? "" : "    " + DATE.format(quest.at())));
            commands.set(row + " #Status.Text", quest.status().name().charAt(0) + quest.status().name().substring(1).toLowerCase(Locale.ROOT));
            commands.set(row + " #Status.Style", switch (quest.status()) {
                case TRACKED -> UiStyles.Tone.GOLD_OUTLINE.style();
                case ACTIVE -> UiStyles.Tone.PURPLE.style();
                case COMPLETED -> UiStyles.Tone.GREEN.style();
                case ABANDONED -> UiStyles.Tone.NEUTRAL.style();
            });
            switch (quest.status()) {
                case ACTIVE, TRACKED -> {
                    rowAction(commands, events, row + " #ActionA", "Complete", "quest-complete", quest.questId(), false);
                    if (quest.status() == PlayerStateAdmin.QuestStatus.ACTIVE) {
                        rowAction(commands, events, row + " #ActionB", "Track", "quest-track", quest.questId(), false);
                    } else {
                        rowAction(commands, events, row + " #ActionB", "Abandon", "quest-abandon", quest.questId(), true);
                    }
                    rowAction(commands, events, row + " #ActionC", "Reset", "quest-reset", quest.questId(), true);
                }
                case COMPLETED -> rowAction(commands, events, row + " #ActionC", "Reset", "quest-reset", quest.questId(), true);
                case ABANDONED -> {
                    rowAction(commands, events, row + " #ActionA", "Allow again", "quest-allow", quest.questId(), false);
                    rowAction(commands, events, row + " #ActionC", "Reset", "quest-reset", quest.questId(), true);
                }
            }
        }
        bind(events, "#StartQuestButton", reasoned("grant").append("@Quest", "#QuestField.Value"));
        bind(events, "#SetObjectiveButton", reasoned("objective").append("@Quest", "#QuestField.Value")
                .append("@Objective", "#ObjectiveField.Value").append("@Value", "#ValueField.Value"));
    }

    private void rowAction(UICommandBuilder commands, UIEventBuilder events, String selector, String label, String action,
                           String target, boolean destructive) {
        boolean armed = (action + "|" + target).equals(pendingConfirm);
        commands.set(selector + ".Visible", true);
        commands.set(selector + ".Text", armed ? "Confirm" : label);
        commands.set(selector + ".Style", destructive ? UiStyles.SMALL_DANGER : UiStyles.SMALL_SECONDARY);
        bind(events, selector, reasoned(action).append("Id", target));
    }

    private void renderState(UICommandBuilder commands, UIEventBuilder events, Snapshot snapshot) {
        commands.clear("#TagRows");
        int index = 0;
        for (String tag : snapshot.tags()) {
            String row = "#TagRows[" + index++ + "]";
            commands.append("#TagRows", VALUE_ROW);
            commands.set(row + " #Key.Text", UiText.oneLine(tag));
            commands.set(row + " #Value.TextSpans", UiText.muted(""));
            removeAction(commands, events, row, reasoned("tag-remove").append("Id", tag));
        }
        if (snapshot.tags().isEmpty()) {
            emptyRow(commands, "#TagRows", "No tags.");
        }
        bind(events, "#AddTagButton", reasoned("tag-add").append("@Tag", "#TagField.Value"));

        commands.clear("#VariableRows");
        index = 0;
        for (Map.Entry<String, String> variable : snapshot.variables().entrySet()) {
            String row = "#VariableRows[" + index++ + "]";
            commands.append("#VariableRows", VALUE_ROW);
            commands.set(row + " #Key.Text", UiText.oneLine(variable.getKey()));
            commands.set(row + " #Value.TextSpans", UiText.text(UiText.oneLine(variable.getValue())));
            removeAction(commands, events, row, reasoned("var-remove").append("Id", variable.getKey()));
        }
        if (snapshot.variables().isEmpty()) {
            emptyRow(commands, "#VariableRows", "No variables.");
        }
        bind(events, "#SetVariableButton", reasoned("var-set").append("@VarKey", "#VarKeyField.Value")
                .append("@VarValue", "#VarValueField.Value"));
    }

    private void renderStory(UICommandBuilder commands, UIEventBuilder events, Snapshot snapshot) {
        commands.set("#StoryUnavailable.Visible", !snapshot.narrative());
        commands.set("#StoryContent.Visible", snapshot.narrative());
        commands.clear("#SessionRows");
        commands.clear("#StoryVariableRows");
        commands.clear("#StoryTagRows");
        if (!snapshot.narrative()) {
            return;
        }
        int index = 0;
        for (SessionLine session : snapshot.sessions()) {
            String row = "#SessionRows[" + index++ + "]";
            commands.append("#SessionRows", VALUE_ROW);
            commands.set(row + " #Key.Text", UiText.oneLine(session.story()));
            commands.set(row + " #Value.TextSpans", Message.empty()
                    .insert(Message.raw(session.status()).color(session.active() ? UiText.GREEN : UiText.MUTED))
                    .insert(Message.raw((session.node().isBlank() ? "" : "  at " + session.node())
                            + (session.party() ? "  (party)" : "")).color(UiText.MUTED)));
            if (session.active()) {
                boolean armed = ("story-restart|" + session.story()).equals(pendingConfirm);
                commands.set(row + " #Action.Visible", true);
                commands.set(row + " #Action.Text", armed ? "Confirm" : "Restart");
                bind(events, row + " #Action", reasoned("story-restart").append("Id", session.story()));
            }
        }
        if (snapshot.sessions().isEmpty()) {
            emptyRow(commands, "#SessionRows", "No story sessions.");
        }
        boolean rewindArmed = pendingConfirm != null && pendingConfirm.startsWith("rewind|");
        commands.set("#RewindButton.Text", rewindArmed ? "Confirm" : "Rewind");
        bind(events, "#RewindButton", reasoned("rewind").append("@Checkpoint", "#CheckpointField.Value"));

        index = 0;
        for (StateLine variable : snapshot.storyVariables()) {
            String row = "#StoryVariableRows[" + index++ + "]";
            commands.append("#StoryVariableRows", VALUE_ROW);
            commands.set(row + " #Key.Text", UiText.oneLine(variable.id()));
            commands.set(row + " #Value.TextSpans", Message.empty()
                    .insert(Message.raw(UiText.oneLine(variable.value())).color(UiText.TEXT))
                    .insert(Message.raw("    " + (variable.preference() ? "setting" : variable.scope())).color(UiText.DIM)));
            if (!variable.preference()) {
                removeAction(commands, events, row, reasoned("story-var-remove").append("Kind", variable.owner()).append("Id", variable.id()));
            }
        }
        if (snapshot.storyVariables().isEmpty()) {
            emptyRow(commands, "#StoryVariableRows", "No story variables.");
        }
        bind(events, "#SetStoryVarButton", reasoned("story-var-set").append("@StoryVar", "#StoryVarField.Value")
                .append("@StoryValue", "#StoryValueField.Value"));

        index = 0;
        for (StateLine tag : snapshot.storyTags()) {
            String row = "#StoryTagRows[" + index++ + "]";
            commands.append("#StoryTagRows", VALUE_ROW);
            commands.set(row + " #Key.Text", UiText.oneLine(tag.id()));
            commands.set(row + " #Value.TextSpans", UiText.of(tag.scope() + (tag.value().isBlank() ? "" : "  " + tag.value()), UiText.DIM));
            removeAction(commands, events, row, reasoned("story-tag-remove").append("Kind", tag.owner()).append("Id", tag.id()));
        }
        if (snapshot.storyTags().isEmpty()) {
            emptyRow(commands, "#StoryTagRows", "No story tags.");
        }
        bind(events, "#AddStoryTagButton", reasoned("story-tag-add").append("@StoryTag", "#StoryTagField.Value"));
        snapshot.problems().forEach(problem -> status = UiText.of(problem, UiText.RED));
    }

    private void removeAction(UICommandBuilder commands, UIEventBuilder events, String row, EventData event) {
        commands.set(row + " #Action.Visible", true);
        commands.set(row + " #Action.Text", "Remove");
        bind(events, row + " #Action", event);
    }

    /** A muted "nothing here" row, so an empty list reads as empty rather than broken. */
    private static void emptyRow(UICommandBuilder commands, String list, String text) {
        commands.append(list, VALUE_ROW);
        commands.set(list + "[0] #Key.Text", text);
        commands.set(list + "[0] #Value.TextSpans", UiText.muted(""));
    }

    private void renderClearDialog(UICommandBuilder commands, UIEventBuilder events, Snapshot snapshot) {
        if (!clearOpen) {
            return;
        }
        commands.set("#ClearTarget.Text", "For " + UiText.oneLine(snapshot.name()) + "  (" + snapshot.player() + ")");
        part(commands, events, "#PartQuests", Part.QUESTS);
        part(commands, events, "#PartTags", Part.TAGS);
        part(commands, events, "#PartVariables", Part.VARIABLES);
        part(commands, events, "#PartStory", Part.STORY_STATE);
        part(commands, events, "#PartSessions", Part.SESSIONS);
        part(commands, events, "#PartPreferences", Part.PREFERENCES);
        commands.set("#ClearSummary.TextSpans", clearParts.isEmpty()
                ? UiText.of("Nothing selected.", UiText.RED)
                : UiText.of(clearParts.size() + (clearParts.size() == 1 ? " part selected" : " parts selected"), UiText.GOLD));
        bind(events, "#CancelClearButton", EventData.of("Action", "clear-cancel"));
        bind(events, "#ConfirmClearButton", new EventData().append("Action", "clear-confirm")
                .append("@ClearReason", "#ClearReasonField.Value"));
    }

    private void part(UICommandBuilder commands, UIEventBuilder events, String selector, Part part) {
        commands.set(selector + ".Style", clearParts.contains(part) ? UiStyles.BUTTON_SELECTED : UiStyles.BUTTON_SECONDARY);
        bind(events, selector, new EventData().append("Action", "clear-part").append("Id", part.name()));
    }

    private void renderBuilder(UICommandBuilder commands, UIEventBuilder events) {
        if (writeFields) {
            QuestDraft current = draft;
            commands.set("#PackageInput.Value", current == null ? "custom" : current.packageId());
            commands.set("#QuestIdInput.Value", current == null ? "" : current.questId());
            commands.set("#QuestTitleInput.Value", current == null ? "" : current.title());
            commands.set("#QuestDescriptionInput.Value", current == null ? "" : current.description());
            commands.set("#StartOnJoinInput.Value", current != null && current.startOnJoin());
            commands.set("#CooldownInput.Value", current == null || current.cooldownSeconds() == null ? "" : current.cooldownSeconds());
            for (int index = 0; index < 4; index++) {
                ObjectiveDraft objective = current != null && index < current.objectives().size()
                        ? current.objectives().get(index) : new ObjectiveDraft("", "", "", "", 1);
                String prefix = "#Objective" + (index + 1);
                commands.set(prefix + "Id.Value", objective.id());
                commands.set(prefix + "Type.Value", objective.type());
                commands.set(prefix + "Title.Value", objective.title());
                commands.set(prefix + "Target.Value", objective.target());
                commands.set(prefix + "Amount.Value", Integer.toString(objective.amount()));
            }
            commands.set("#ExtraObjectivesInput.Value", current == null ? "[]" : current.extraObjectivesJson());
            commands.set("#StartConditionsInput.Value", current == null ? "[]" : current.startConditionsJson());
            commands.set("#StartEventsInput.Value", current == null ? "[]" : current.startEventsJson());
            commands.set("#CompleteEventsInput.Value", current == null ? "[]" : current.completeEventsJson());
            commands.set("#RewardsInput.Value", current == null ? "[]" : current.rewardsJson());
            commands.set("#ReacceptConditionsInput.Value", current == null ? "[]" : current.reacceptConditionsJson());
        }
        bind(events, "#NewQuestButton", EventData.of("Action", "builder-new"));
        bind(events, "#LoadQuestButton", new EventData().append("Action", "builder-load")
                .append("@Package", "#PackageInput.Value").append("@QuestId", "#QuestIdInput.Value"));
        EventData publish = new EventData().append("Action", "builder-publish")
                .append("@Package", "#PackageInput.Value")
                .append("@QuestId", "#QuestIdInput.Value")
                .append("@Title", "#QuestTitleInput.Value")
                .append("@Description", "#QuestDescriptionInput.Value")
                .append("@StartOnJoin", "#StartOnJoinInput.Value")
                .append("@Cooldown", "#CooldownInput.Value")
                .append("@ExtraObjectives", "#ExtraObjectivesInput.Value")
                .append("@StartConditions", "#StartConditionsInput.Value")
                .append("@StartEvents", "#StartEventsInput.Value")
                .append("@CompleteEvents", "#CompleteEventsInput.Value")
                .append("@Rewards", "#RewardsInput.Value")
                .append("@ReacceptConditions", "#ReacceptConditionsInput.Value");
        for (int index = 1; index <= 4; index++) {
            publish.append("@Objective" + index + "Id", "#Objective" + index + "Id.Value")
                    .append("@Objective" + index + "Type", "#Objective" + index + "Type.Value")
                    .append("@Objective" + index + "Title", "#Objective" + index + "Title.Value")
                    .append("@Objective" + index + "Target", "#Objective" + index + "Target.Value")
                    .append("@Objective" + index + "Amount", "#Objective" + index + "Amount.Value");
        }
        bind(events, "#PublishQuestButton", publish);
    }

    private void renderScripts(UICommandBuilder commands, UIEventBuilder events) {
        if (writeFields) {
            commands.set("#ScriptPackageInput.Value", scriptPackage);
            commands.set("#ScriptFileInput.Value", scriptFile);
            commands.set("#ScriptSourceInput.Value", scriptSource);
        }
        bind(events, "#LoadScriptButton", new EventData().append("Action", "scripts-load")
                .append("@ScriptPackage", "#ScriptPackageInput.Value").append("@ScriptFile", "#ScriptFileInput.Value"));
        bind(events, "#PublishScriptButton", new EventData().append("Action", "scripts-publish")
                .append("@ScriptPackage", "#ScriptPackageInput.Value").append("@ScriptFile", "#ScriptFileInput.Value")
                .append("@ScriptSource", "#ScriptSourceInput.Value"));
    }

    // --- Helpers ---

    /** An action that changes a player, carrying the reason typed above the tabs. */
    private static EventData reasoned(String action) {
        return new EventData().append("Action", action).append("@Reason", "#ReasonField.Value");
    }

    private static void bind(UIEventBuilder events, String selector, EventData data) {
        events.addEventBinding(CustomUIEventBindingType.Activating, selector, data);
    }

    private static <E extends Enum<E>> E parse(Class<E> type, String raw, E fallback) {
        try {
            return Enum.valueOf(type, raw);
        } catch (IllegalArgumentException unknown) {
            return fallback;
        }
    }

    private static int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(value.trim());
        } catch (NumberFormatException notNumber) {
            return fallback;
        }
    }

    private static String json(String value) {
        return value == null || value.isBlank() ? "[]" : value;
    }

    private static String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value.trim();
    }

    // --- Event payload ---

    private static BuilderCodec<PageEventData> eventCodec() {
        BuilderCodec.Builder<PageEventData> codec = BuilderCodec.builder(PageEventData.class, PageEventData::new);
        string(codec, "Action", (data, value) -> data.action = value);
        string(codec, "Id", (data, value) -> data.id = value);
        string(codec, "Kind", (data, value) -> data.kind = value);
        string(codec, "@Find", (data, value) -> data.find = value);
        string(codec, "@Reason", (data, value) -> data.reason = value);
        string(codec, "@ClearReason", (data, value) -> data.clearReason = value);
        string(codec, "@Quest", (data, value) -> data.quest = value);
        string(codec, "@Objective", (data, value) -> data.objective = value);
        string(codec, "@Value", (data, value) -> data.value = value);
        string(codec, "@Tag", (data, value) -> data.tag = value);
        string(codec, "@VarKey", (data, value) -> data.varKey = value);
        string(codec, "@VarValue", (data, value) -> data.varValue = value);
        string(codec, "@StoryVar", (data, value) -> data.storyVar = value);
        string(codec, "@StoryValue", (data, value) -> data.storyValue = value);
        string(codec, "@StoryTag", (data, value) -> data.storyTag = value);
        string(codec, "@Checkpoint", (data, value) -> data.checkpoint = value);
        string(codec, "@Package", (data, value) -> data.packageId = value);
        string(codec, "@QuestId", (data, value) -> data.questId = value);
        string(codec, "@Title", (data, value) -> data.title = value);
        string(codec, "@Description", (data, value) -> data.description = value);
        string(codec, "@Cooldown", (data, value) -> data.cooldown = value);
        string(codec, "@ExtraObjectives", (data, value) -> data.extraObjectives = value);
        string(codec, "@StartConditions", (data, value) -> data.startConditions = value);
        string(codec, "@StartEvents", (data, value) -> data.startEvents = value);
        string(codec, "@CompleteEvents", (data, value) -> data.completeEvents = value);
        string(codec, "@Rewards", (data, value) -> data.rewards = value);
        string(codec, "@ReacceptConditions", (data, value) -> data.reacceptConditions = value);
        string(codec, "@ScriptPackage", (data, value) -> data.scriptPackage = value);
        string(codec, "@ScriptFile", (data, value) -> data.scriptFile = value);
        string(codec, "@ScriptSource", (data, value) -> data.scriptSource = value);
        codec.append(new KeyedCodec<>("@StartOnJoin", Codec.BOOLEAN), (data, value) -> data.startOnJoin = value != null && value,
                data -> data.startOnJoin).add();
        for (int index = 1; index <= 4; index++) {
            int slot = index - 1;
            string(codec, "@Objective" + index + "Id", (data, value) -> data.objectiveIds[slot] = value);
            string(codec, "@Objective" + index + "Type", (data, value) -> data.objectiveTypes[slot] = value);
            string(codec, "@Objective" + index + "Title", (data, value) -> data.objectiveTitles[slot] = value);
            string(codec, "@Objective" + index + "Target", (data, value) -> data.objectiveTargets[slot] = value);
            string(codec, "@Objective" + index + "Amount", (data, value) -> data.objectiveAmounts[slot] = value);
        }
        return codec.build();
    }

    private static void string(BuilderCodec.Builder<PageEventData> codec, String key,
                               java.util.function.BiConsumer<PageEventData, String> setter) {
        codec.append(new KeyedCodec<>(key, Codec.STRING), setter::accept, data -> null).add();
    }

    public static final class PageEventData {
        private String action;
        private String id;
        private String kind;
        private String find;
        private String reason;
        private String clearReason;
        private String quest;
        private String objective;
        private String value;
        private String tag;
        private String varKey;
        private String varValue;
        private String storyVar;
        private String storyValue;
        private String storyTag;
        private String checkpoint;
        private String packageId;
        private String questId;
        private String title;
        private String description;
        private String cooldown;
        private boolean startOnJoin;
        private String extraObjectives;
        private String startConditions;
        private String startEvents;
        private String completeEvents;
        private String rewards;
        private String reacceptConditions;
        private String scriptPackage;
        private String scriptFile;
        private String scriptSource;
        private final String[] objectiveIds = new String[4];
        private final String[] objectiveTypes = new String[4];
        private final String[] objectiveTitles = new String[4];
        private final String[] objectiveTargets = new String[4];
        private final String[] objectiveAmounts = new String[4];

        private static String text(String value) {
            return value == null ? "" : value;
        }

        public String action() { return text(action); }
        public String id() { return text(id); }
        public String kind() { return text(kind); }
        public String find() { return text(find); }
        public String reason() { return text(reason); }
        public String clearReason() { return text(clearReason); }
        public String quest() { return text(quest); }
        public String objective() { return text(objective); }
        public String value() { return text(value); }
        public String tag() { return text(tag); }
        public String varKey() { return text(varKey); }
        public String varValue() { return text(varValue); }
        public String storyVar() { return text(storyVar); }
        public String storyValue() { return text(storyValue); }
        public String storyTag() { return text(storyTag); }
        public String checkpoint() { return text(checkpoint); }
        public String packageId() { return text(packageId); }
        public String questId() { return text(questId); }
        public String title() { return text(title); }
        public String description() { return text(description); }
        public String cooldown() { return text(cooldown); }
        public boolean startOnJoin() { return startOnJoin; }
        public String extraObjectives() { return text(extraObjectives); }
        public String startConditions() { return text(startConditions); }
        public String startEvents() { return text(startEvents); }
        public String completeEvents() { return text(completeEvents); }
        public String rewards() { return text(rewards); }
        public String reacceptConditions() { return text(reacceptConditions); }
        public String scriptPackage() { return text(scriptPackage); }
        public String scriptFile() { return text(scriptFile); }
        public String scriptSource() { return text(scriptSource); }
        public String objectiveId(int index) { return text(objectiveIds[index]); }
        public String objectiveType(int index) { return text(objectiveTypes[index]); }
        public String objectiveTitle(int index) { return text(objectiveTitles[index]); }
        public String objectiveTarget(int index) { return text(objectiveTargets[index]); }
        public String objectiveAmount(int index) { return text(objectiveAmounts[index]); }
    }
}
