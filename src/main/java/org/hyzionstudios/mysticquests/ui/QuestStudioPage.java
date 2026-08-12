package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.ObjectiveDraft;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.QuestDraft;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.SaveResult;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService.SourceSaveResult;
import org.hyzionstudios.mysticquests.service.QuestResult;
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

import java.io.IOException;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/** In-game quest authoring and player support console. */
public final class QuestStudioPage extends InteractiveCustomUIPage<QuestStudioPage.PageEventData> {
    private static final BuilderCodec<PageEventData> EVENT_CODEC = eventCodec();

    private final MysticQuestsRuntime runtime;
    private final UUID adminId;
    private Tab tab = Tab.BUILDER;
    private QuestDraft draft;
    private UUID selectedPlayer;
    private String status = "Ready";
    private String playerTarget = "self";
    private String playerQuest = "";
    private String playerObjective = "";
    private String playerProgress = "0";
    private String playerTag = "";
    private String playerVariableKey = "";
    private String playerVariableValue = "";
    private String scriptPackage = "custom";
    private String scriptFile = "scripts.yml";
    private String scriptSource = "# Named scripting elements\nconditions: {}\nactions: {}\nobjectives: {}\nquests: []\n";

    public QuestStudioPage(PlayerRef playerRef, MysticQuestsRuntime runtime) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EVENT_CODEC);
        this.runtime = runtime;
        this.adminId = playerRef.getUuid();
        this.selectedPlayer = adminId;
    }

    @Override
    public void build(Ref<EntityStore> playerEntity, UICommandBuilder builder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        render(builder, eventBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerEntity, Store<EntityStore> store, PageEventData data) {
        String action = safe(data.action()).toLowerCase();
        if (action.equals("tab:builder")) {
            tab = Tab.BUILDER;
            status = "Quest builder ready";
        } else if (action.equals("tab:players")) {
            tab = Tab.PLAYERS;
            status = "Player state editor ready";
        } else if (action.equals("tab:scripts")) {
            tab = Tab.SCRIPTS;
            status = "YAML package editor ready";
        } else if (action.equals("builder:new")) {
            draft = null;
            status = "Cleared the form";
        } else if (action.equals("builder:load")) {
            loadDraft(data);
        } else if (action.equals("builder:publish")) {
            publishDraft(data);
        } else if (action.equals("scripts:load")) {
            loadScript(data);
        } else if (action.equals("scripts:publish")) {
            publishScript(data);
        } else if (action.startsWith("player:")) {
            handlePlayerAction(action.substring("player:".length()), data);
        }

        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        render(builder, eventBuilder);
        sendUpdate(builder, eventBuilder, true);
    }

    private void loadScript(PageEventData data) {
        scriptPackage = blankDefault(data.scriptPackage(), "custom");
        scriptFile = blankDefault(data.scriptFile(), "scripts.yml");
        try {
            scriptSource = runtime.authoringService().loadSource(scriptPackage, scriptFile);
            status = "Loaded " + scriptPackage + "/" + scriptFile;
        } catch (IOException exception) {
            status = exception.getMessage();
        }
    }

    private void publishScript(PageEventData data) {
        scriptPackage = blankDefault(data.scriptPackage(), "custom");
        scriptFile = blankDefault(data.scriptFile(), "scripts.yml");
        scriptSource = safe(data.scriptSource());
        try {
            SourceSaveResult result = runtime.authoringService().saveSource(scriptPackage, scriptFile, scriptSource);
            status = "Published " + result.file().getFileName() + " • " + result.loadedQuestCount() + " quests loaded";
        } catch (IOException exception) {
            status = exception.getMessage();
        }
    }

    private void loadDraft(PageEventData data) {
        try {
            draft = runtime.authoringService().load(data.packageId(), data.questId());
            status = "Loaded " + draft.packageId() + ":" + draft.questId();
        } catch (IOException exception) {
            status = exception.getMessage();
        }
    }

    private void publishDraft(PageEventData data) {
        try {
            draft = draftFrom(data);
            SaveResult result = runtime.authoringService().save(draft);
            status = "Published " + result.questId() + " • " + result.loadedQuestCount() + " quests loaded";
        } catch (IOException exception) {
            status = exception.getMessage();
        }
    }

    private QuestDraft draftFrom(PageEventData data) {
        List<ObjectiveDraft> objectives = new ArrayList<>();
        for (int index = 0; index < 4; index++) {
            objectives.add(new ObjectiveDraft(
                    data.objectiveId(index),
                    data.objectiveType(index),
                    data.objectiveTitle(index),
                    data.objectiveTarget(index),
                    parseInt(data.objectiveAmount(index), 1)));
        }
        return new QuestDraft(
                data.packageId(), data.questId(), data.title(), data.description(), data.startOnJoin(),
                data.cooldown(), objectives, data.extraObjectives(), data.startConditions(), data.startEvents(),
                data.completeEvents(), data.rewards(), data.reacceptConditions());
    }

    private void handlePlayerAction(String action, PageEventData data) {
        rememberPlayerForm(data);
        UUID target = resolvePlayer(playerTarget);
        if (target == null) {
            status = "Unknown player. Use an online name, UUID, or self.";
            return;
        }
        selectedPlayer = target;
        QuestResult result = null;
        switch (action) {
            case "refresh" -> status = "Loaded player " + target;
            case "start" -> result = runtime.questService().startQuest(target, playerQuest);
            case "complete" -> result = runtime.questService().completeQuest(target, playerQuest);
            case "abandon" -> result = runtime.questService().abandonQuest(target, playerQuest);
            case "clearabandoned" -> result = runtime.questService().clearAbandoned(target, playerQuest);
            case "reset" -> result = runtime.questService().resetQuestState(target, playerQuest);
            case "track" -> result = runtime.questService().trackQuest(target, playerQuest);
            case "untrack" -> result = runtime.questService().untrackQuest(target);
            case "progress" -> result = runtime.questService().setObjectiveProgress(
                    target, playerQuest, playerObjective, parseInt(playerProgress, 0));
            case "addtag" -> status = runtime.scopedStateService().addTag("player", target.toString(), playerTag)
                    ? "Added tag " + playerTag : "Tag was already present or invalid";
            case "removetag" -> status = runtime.scopedStateService().removeTag("player", target.toString(), playerTag)
                    ? "Removed tag " + playerTag : "Tag was not present";
            case "setvariable" -> {
                runtime.scopedStateService().setVariable(
                        "player", target.toString(), playerVariableKey, playerVariableValue);
                status = "Set variable " + playerVariableKey;
            }
            case "removevariable" -> status = runtime.scopedStateService().removeVariable(
                    "player", target.toString(), playerVariableKey)
                    ? "Removed variable " + playerVariableKey : "Variable was not present";
            default -> status = "Unknown player action: " + action;
        }
        if (result != null) {
            status = result.message();
        }
    }

    private void rememberPlayerForm(PageEventData data) {
        playerTarget = blankDefault(data.playerTarget(), playerTarget);
        playerQuest = safe(data.playerQuest());
        playerObjective = safe(data.playerObjective());
        playerProgress = blankDefault(data.playerProgress(), "0");
        playerTag = safe(data.playerTag());
        playerVariableKey = safe(data.playerVariableKey());
        playerVariableValue = safe(data.playerVariableValue());
    }

    private UUID resolvePlayer(String token) {
        if (token == null || token.isBlank() || token.equalsIgnoreCase("self")) {
            return adminId;
        }
        try {
            return UUID.fromString(token.trim());
        } catch (IllegalArgumentException ignored) {
            return runtime.resolveOnlinePlayer(token.trim()).orElse(null);
        }
    }

    private void render(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.append("mysticquests/Pages/QuestStudioPage.ui");
        builder.set("#StudioStatus.Text", status);
        builder.set("#BuilderPanel.Visible", tab == Tab.BUILDER);
        builder.set("#PlayerPanel.Visible", tab == Tab.PLAYERS);
        builder.set("#ScriptsPanel.Visible", tab == Tab.SCRIPTS);
        bind(eventBuilder, "#BuilderTab", EventData.of("Action", "tab:builder"));
        bind(eventBuilder, "#PlayersTab", EventData.of("Action", "tab:players"));
        bind(eventBuilder, "#ScriptsTab", EventData.of("Action", "tab:scripts"));

        if (tab == Tab.BUILDER) {
            renderBuilder(builder, eventBuilder);
        } else if (tab == Tab.PLAYERS) {
            renderPlayer(builder, eventBuilder);
        } else {
            renderScripts(builder, eventBuilder);
        }
    }

    private void renderScripts(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.set("#ScriptPackageInput.Value", scriptPackage);
        builder.set("#ScriptFileInput.Value", scriptFile);
        builder.set("#ScriptSourceInput.Value", scriptSource);
        EventData fields = new EventData()
                .append("ScriptPackage", "#ScriptPackageInput.Value")
                .append("ScriptFile", "#ScriptFileInput.Value");
        bind(eventBuilder, "#LoadScriptButton", fields.append("Action", "scripts:load"));
        bind(eventBuilder, "#PublishScriptButton", new EventData()
                .append("Action", "scripts:publish")
                .append("ScriptPackage", "#ScriptPackageInput.Value")
                .append("ScriptFile", "#ScriptFileInput.Value")
                .append("ScriptSource", "#ScriptSourceInput.Value"));
    }

    private void renderBuilder(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        if (draft != null) {
            builder.set("#PackageInput.Value", draft.packageId());
            builder.set("#QuestIdInput.Value", draft.questId());
            builder.set("#QuestTitleInput.Value", draft.title());
            builder.set("#QuestDescriptionInput.Value", draft.description());
            builder.set("#StartOnJoinInput.Value", draft.startOnJoin());
            builder.set("#CooldownInput.Value", draft.cooldownSeconds());
            for (int index = 0; index < 4; index++) {
                ObjectiveDraft objective = index < draft.objectives().size()
                        ? draft.objectives().get(index)
                        : new ObjectiveDraft("", "", "", "", 1);
                int uiIndex = index + 1;
                builder.set("#Objective" + uiIndex + "Id.Value", objective.id());
                builder.set("#Objective" + uiIndex + "Type.Value", objective.type());
                builder.set("#Objective" + uiIndex + "Title.Value", objective.title());
                builder.set("#Objective" + uiIndex + "Target.Value", objective.target());
                builder.set("#Objective" + uiIndex + "Amount.Value", Integer.toString(objective.amount()));
            }
            builder.set("#ExtraObjectivesInput.Value", draft.extraObjectivesJson());
            builder.set("#StartConditionsInput.Value", draft.startConditionsJson());
            builder.set("#StartEventsInput.Value", draft.startEventsJson());
            builder.set("#CompleteEventsInput.Value", draft.completeEventsJson());
            builder.set("#RewardsInput.Value", draft.rewardsJson());
            builder.set("#ReacceptConditionsInput.Value", draft.reacceptConditionsJson());
        }
        bind(eventBuilder, "#NewQuestButton", EventData.of("Action", "builder:new"));
        bind(eventBuilder, "#LoadQuestButton", new EventData()
                .append("Action", "builder:load")
                .append("Package", "#PackageInput.Value")
                .append("QuestId", "#QuestIdInput.Value"));
        bind(eventBuilder, "#PublishQuestButton", builderEventData());
    }

    private EventData builderEventData() {
        EventData data = new EventData()
                .append("Action", "builder:publish")
                .append("Package", "#PackageInput.Value")
                .append("QuestId", "#QuestIdInput.Value")
                .append("Title", "#QuestTitleInput.Value")
                .append("Description", "#QuestDescriptionInput.Value")
                .append("StartOnJoin", "#StartOnJoinInput.Value")
                .append("Cooldown", "#CooldownInput.Value")
                .append("ExtraObjectives", "#ExtraObjectivesInput.Value")
                .append("StartConditions", "#StartConditionsInput.Value")
                .append("StartEvents", "#StartEventsInput.Value")
                .append("CompleteEvents", "#CompleteEventsInput.Value")
                .append("Rewards", "#RewardsInput.Value")
                .append("ReacceptConditions", "#ReacceptConditionsInput.Value");
        for (int index = 1; index <= 4; index++) {
            data.append("Objective" + index + "Id", "#Objective" + index + "Id.Value")
                    .append("Objective" + index + "Type", "#Objective" + index + "Type.Value")
                    .append("Objective" + index + "Title", "#Objective" + index + "Title.Value")
                    .append("Objective" + index + "Target", "#Objective" + index + "Target.Value")
                    .append("Objective" + index + "Amount", "#Objective" + index + "Amount.Value");
        }
        return data;
    }

    private void renderPlayer(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.set("#PlayerTargetInput.Value", playerTarget);
        builder.set("#PlayerQuestInput.Value", playerQuest);
        builder.set("#ObjectiveProgressIdInput.Value", playerObjective);
        builder.set("#ObjectiveProgressValueInput.Value", playerProgress);
        builder.set("#PlayerTagInput.Value", playerTag);
        builder.set("#PlayerVariableKeyInput.Value", playerVariableKey);
        builder.set("#PlayerVariableValueInput.Value", playerVariableValue);
        builder.set("#PlayerSummary.Text", playerSummary());

        bindPlayer(eventBuilder, "#PlayerRefreshButton", "refresh");
        bindPlayer(eventBuilder, "#GrantQuestButton", "start");
        bindPlayer(eventBuilder, "#CompleteQuestButton", "complete");
        bindPlayer(eventBuilder, "#AbandonQuestButton", "abandon");
        bindPlayer(eventBuilder, "#ResetQuestButton", "reset");
        bindPlayer(eventBuilder, "#TrackQuestButton", "track");
        bindPlayer(eventBuilder, "#UntrackQuestButton", "untrack");
        bindPlayer(eventBuilder, "#ClearAbandonedButton", "clearabandoned");
        bindPlayer(eventBuilder, "#SetProgressButton", "progress");
        bindPlayer(eventBuilder, "#AddTagButton", "addtag");
        bindPlayer(eventBuilder, "#RemoveTagButton", "removetag");
        bindPlayer(eventBuilder, "#SetVariableButton", "setvariable");
        bindPlayer(eventBuilder, "#RemoveVariableButton", "removevariable");
    }

    private void bindPlayer(UIEventBuilder eventBuilder, String selector, String action) {
        bind(eventBuilder, selector, new EventData()
                .append("Action", "player:" + action)
                .append("PlayerTarget", "#PlayerTargetInput.Value")
                .append("PlayerQuest", "#PlayerQuestInput.Value")
                .append("PlayerObjective", "#ObjectiveProgressIdInput.Value")
                .append("PlayerProgress", "#ObjectiveProgressValueInput.Value")
                .append("PlayerTag", "#PlayerTagInput.Value")
                .append("PlayerVariableKey", "#PlayerVariableKeyInput.Value")
                .append("PlayerVariableValue", "#PlayerVariableValueInput.Value"));
    }

    private String playerSummary() {
        if (selectedPlayer == null) {
            return "Select a player to inspect live state.";
        }
        PlayerQuestData data = runtime.questService().data(selectedPlayer);
        Set<String> tags = runtime.scopedStateService().tags("player", selectedPlayer.toString());
        Map<String, String> variables = runtime.scopedStateService().variables("player", selectedPlayer.toString());
        return "PLAYER  " + selectedPlayer
                + "\n\nACTIVE  " + format(data.activeQuests().keySet())
                + "\n\nCOMPLETED  " + format(data.completedQuests().keySet())
                + "\n\nABANDONED  " + format(data.abandonedQuests().keySet())
                + "\n\nTRACKED  " + (data.trackedQuestId() == null ? "none" : data.trackedQuestId())
                + "\n\nTAGS  " + format(tags)
                + "\n\nVARIABLES  " + (variables.isEmpty() ? "none" : variables);
    }

    private String format(Set<String> values) {
        return values.isEmpty() ? "none" : String.join(", ", values);
    }

    private void bind(UIEventBuilder eventBuilder, String selector, EventData data) {
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, selector, data);
    }

    private int parseInt(String value, int fallback) {
        try {
            return Integer.parseInt(safe(value).trim());
        } catch (NumberFormatException exception) {
            return fallback;
        }
    }

    private String safe(String value) {
        return value == null ? "" : value;
    }

    private String blankDefault(String value, String fallback) {
        return value == null || value.isBlank() ? fallback : value;
    }

    private enum Tab { BUILDER, SCRIPTS, PLAYERS }

    private static BuilderCodec<PageEventData> eventCodec() {
        BuilderCodec.Builder<PageEventData> codec = BuilderCodec.builder(PageEventData.class, PageEventData::new)
                .addField(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action)
                .addField(new KeyedCodec<>("Package", Codec.STRING), PageEventData::setPackageId, PageEventData::packageId)
                .addField(new KeyedCodec<>("QuestId", Codec.STRING), PageEventData::setQuestId, PageEventData::questId)
                .addField(new KeyedCodec<>("Title", Codec.STRING), PageEventData::setTitle, PageEventData::title)
                .addField(new KeyedCodec<>("Description", Codec.STRING), PageEventData::setDescription, PageEventData::description)
                .addField(new KeyedCodec<>("StartOnJoin", Codec.BOOLEAN), PageEventData::setStartOnJoin, PageEventData::startOnJoin)
                .addField(new KeyedCodec<>("Cooldown", Codec.STRING), PageEventData::setCooldown, PageEventData::cooldown)
                .addField(new KeyedCodec<>("ExtraObjectives", Codec.STRING), PageEventData::setExtraObjectives, PageEventData::extraObjectives)
                .addField(new KeyedCodec<>("StartConditions", Codec.STRING), PageEventData::setStartConditions, PageEventData::startConditions)
                .addField(new KeyedCodec<>("StartEvents", Codec.STRING), PageEventData::setStartEvents, PageEventData::startEvents)
                .addField(new KeyedCodec<>("CompleteEvents", Codec.STRING), PageEventData::setCompleteEvents, PageEventData::completeEvents)
                .addField(new KeyedCodec<>("Rewards", Codec.STRING), PageEventData::setRewards, PageEventData::rewards)
                .addField(new KeyedCodec<>("ReacceptConditions", Codec.STRING), PageEventData::setReacceptConditions, PageEventData::reacceptConditions)
                .addField(new KeyedCodec<>("ScriptPackage", Codec.STRING), PageEventData::setScriptPackage, PageEventData::scriptPackage)
                .addField(new KeyedCodec<>("ScriptFile", Codec.STRING), PageEventData::setScriptFile, PageEventData::scriptFile)
                .addField(new KeyedCodec<>("ScriptSource", Codec.STRING), PageEventData::setScriptSource, PageEventData::scriptSource)
                .addField(new KeyedCodec<>("PlayerTarget", Codec.STRING), PageEventData::setPlayerTarget, PageEventData::playerTarget)
                .addField(new KeyedCodec<>("PlayerQuest", Codec.STRING), PageEventData::setPlayerQuest, PageEventData::playerQuest)
                .addField(new KeyedCodec<>("PlayerObjective", Codec.STRING), PageEventData::setPlayerObjective, PageEventData::playerObjective)
                .addField(new KeyedCodec<>("PlayerProgress", Codec.STRING), PageEventData::setPlayerProgress, PageEventData::playerProgress)
                .addField(new KeyedCodec<>("PlayerTag", Codec.STRING), PageEventData::setPlayerTag, PageEventData::playerTag)
                .addField(new KeyedCodec<>("PlayerVariableKey", Codec.STRING), PageEventData::setPlayerVariableKey, PageEventData::playerVariableKey)
                .addField(new KeyedCodec<>("PlayerVariableValue", Codec.STRING), PageEventData::setPlayerVariableValue, PageEventData::playerVariableValue);
        for (int index = 1; index <= 4; index++) {
            int objective = index - 1;
            codec.addField(new KeyedCodec<>("Objective" + index + "Id", Codec.STRING),
                    (data, value) -> data.objectiveIds[objective] = value, data -> data.objectiveIds[objective]);
            codec.addField(new KeyedCodec<>("Objective" + index + "Type", Codec.STRING),
                    (data, value) -> data.objectiveTypes[objective] = value, data -> data.objectiveTypes[objective]);
            codec.addField(new KeyedCodec<>("Objective" + index + "Title", Codec.STRING),
                    (data, value) -> data.objectiveTitles[objective] = value, data -> data.objectiveTitles[objective]);
            codec.addField(new KeyedCodec<>("Objective" + index + "Target", Codec.STRING),
                    (data, value) -> data.objectiveTargets[objective] = value, data -> data.objectiveTargets[objective]);
            codec.addField(new KeyedCodec<>("Objective" + index + "Amount", Codec.STRING),
                    (data, value) -> data.objectiveAmounts[objective] = value, data -> data.objectiveAmounts[objective]);
        }
        return codec.build();
    }

    public static final class PageEventData {
        private String action = "";
        private String packageId = "";
        private String questId = "";
        private String title = "";
        private String description = "";
        private boolean startOnJoin;
        private String cooldown = "";
        private String extraObjectives = "[]";
        private String startConditions = "[]";
        private String startEvents = "[]";
        private String completeEvents = "[]";
        private String rewards = "[]";
        private String reacceptConditions = "[]";
        private String scriptPackage = "";
        private String scriptFile = "";
        private String scriptSource = "";
        private String playerTarget = "";
        private String playerQuest = "";
        private String playerObjective = "";
        private String playerProgress = "";
        private String playerTag = "";
        private String playerVariableKey = "";
        private String playerVariableValue = "";
        private final String[] objectiveIds = {"", "", "", ""};
        private final String[] objectiveTypes = {"", "", "", ""};
        private final String[] objectiveTitles = {"", "", "", ""};
        private final String[] objectiveTargets = {"", "", "", ""};
        private final String[] objectiveAmounts = {"1", "1", "1", "1"};

        public String action() { return action; }
        public void setAction(String value) { action = value; }
        public String packageId() { return packageId; }
        public void setPackageId(String value) { packageId = value; }
        public String questId() { return questId; }
        public void setQuestId(String value) { questId = value; }
        public String title() { return title; }
        public void setTitle(String value) { title = value; }
        public String description() { return description; }
        public void setDescription(String value) { description = value; }
        public boolean startOnJoin() { return startOnJoin; }
        public void setStartOnJoin(boolean value) { startOnJoin = value; }
        public String cooldown() { return cooldown; }
        public void setCooldown(String value) { cooldown = value; }
        public String extraObjectives() { return extraObjectives; }
        public void setExtraObjectives(String value) { extraObjectives = value; }
        public String startConditions() { return startConditions; }
        public void setStartConditions(String value) { startConditions = value; }
        public String startEvents() { return startEvents; }
        public void setStartEvents(String value) { startEvents = value; }
        public String completeEvents() { return completeEvents; }
        public void setCompleteEvents(String value) { completeEvents = value; }
        public String rewards() { return rewards; }
        public void setRewards(String value) { rewards = value; }
        public String reacceptConditions() { return reacceptConditions; }
        public void setReacceptConditions(String value) { reacceptConditions = value; }
        public String scriptPackage() { return scriptPackage; }
        public void setScriptPackage(String value) { scriptPackage = value; }
        public String scriptFile() { return scriptFile; }
        public void setScriptFile(String value) { scriptFile = value; }
        public String scriptSource() { return scriptSource; }
        public void setScriptSource(String value) { scriptSource = value; }
        public String playerTarget() { return playerTarget; }
        public void setPlayerTarget(String value) { playerTarget = value; }
        public String playerQuest() { return playerQuest; }
        public void setPlayerQuest(String value) { playerQuest = value; }
        public String playerObjective() { return playerObjective; }
        public void setPlayerObjective(String value) { playerObjective = value; }
        public String playerProgress() { return playerProgress; }
        public void setPlayerProgress(String value) { playerProgress = value; }
        public String playerTag() { return playerTag; }
        public void setPlayerTag(String value) { playerTag = value; }
        public String playerVariableKey() { return playerVariableKey; }
        public void setPlayerVariableKey(String value) { playerVariableKey = value; }
        public String playerVariableValue() { return playerVariableValue; }
        public void setPlayerVariableValue(String value) { playerVariableValue = value; }
        public String objectiveId(int index) { return objectiveIds[index]; }
        public String objectiveType(int index) { return objectiveTypes[index]; }
        public String objectiveTitle(int index) { return objectiveTitles[index]; }
        public String objectiveTarget(int index) { return objectiveTargets[index]; }
        public String objectiveAmount(int index) { return objectiveAmounts[index]; }
    }
}
