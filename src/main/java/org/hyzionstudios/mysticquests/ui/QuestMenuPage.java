package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestResult;

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

public final class QuestMenuPage extends InteractiveCustomUIPage<QuestMenuPage.PageEventData> {
    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .addField(new KeyedCodec<>("Quest", Codec.STRING), PageEventData::setQuest, PageEventData::quest)
            .addField(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action)
            .build();

    private final UUID playerId;
    private final PlayerQuestService questService;
    private String selectedQuestId;
    /** Feedback from the last accept attempt, shown until the next interaction. */
    private String statusMessage = "";

    public QuestMenuPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService) {
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
        if (!data.quest().isBlank() && !data.quest().equals(selectedQuestId)) {
            selectedQuestId = data.quest();
            statusMessage = "";
        }
        if (data.action().equalsIgnoreCase("accept") && selectedQuestId != null) {
            QuestResult result = questService.startQuest(playerId, selectedQuestId);
            statusMessage = result.message();
            if (result.success()) {
                // The quest leaves the board once accepted; fall back to the next available one.
                selectedQuestId = null;
            }
        }
        UICommandBuilder builder = new UICommandBuilder();
        UIEventBuilder eventBuilder = new UIEventBuilder();
        render(builder, eventBuilder);
        sendUpdate(builder, eventBuilder, true);
    }

    private void render(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.append("mysticquests/Pages/QuestMenuPage.ui");

        List<JournalEntry> entries = questService.availableJournal(playerId);

        if (entries.isEmpty()) {
            builder.set("#BoardEmptyState.Visible", true);
            builder.set("#QuestBoardContent.Visible", false);
            builder.set("#BoardEmptyBody.Text", statusMessage.isBlank()
                    ? "Explore the world or speak with a quest giver. Active quests remain available in your journal."
                    : statusMessage);
            builder.set("#AcceptButton.Visible", false);
            return;
        }

        builder.set("#BoardEmptyState.Visible", false);
        builder.set("#QuestBoardContent.Visible", true);

        if (selectedQuestId == null || entries.stream().noneMatch(entry -> entry.questId().equals(selectedQuestId))) {
            selectedQuestId = entries.getFirst().questId();
        }

        for (int index = 0; index < entries.size(); index++) {
            JournalEntry entry = entries.get(index);
            String cardId = "QuestCard" + index;
            boolean selected = entry.questId().equals(selectedQuestId);
            builder.appendInline("#QuestCardList", questCard(cardId, selected, entry));
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + cardId,
                    EventData.of("Quest", entry.questId()));
        }

        JournalEntry selected = entries.stream()
                .filter(entry -> entry.questId().equals(selectedQuestId))
                .findFirst()
                .orElse(entries.getFirst());
        builder.set("#PreviewQuestName.Text", selected.displayName());
        builder.set("#PreviewQuestDescription.Text", statusMessage.isBlank() ? selected.description() : statusMessage);
        builder.set("#AcceptButton.Visible", true);
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#AcceptButton",
                EventData.of("Action", "accept"));
    }

    private String questCard(String id, boolean selected, JournalEntry entry) {
        return """
                Button #%s {
                  Anchor: (Height: 88, Bottom: 6);
                  LayoutMode: Top;
                  Style: %s;
                  Padding: (Horizontal: 14, Top: 9, Bottom: 8);

                  Label #QuestName {
                    Text: "%s";
                    Style: (FontSize: 16, RenderBold: true, TextColor: %s, Wrap: true);
                    Anchor: (Bottom: 4);
                  }

                  Label #QuestId {
                    Text: "%s";
                    Style: (FontSize: 11, TextColor: %s);
                    Anchor: (Bottom: 10);
                  }

                  Label #QuestSummary {
                    Text: "%s";
                    Style: (FontSize: 13, TextColor: %s, Wrap: true);
                  }
                }
                """.formatted(
                id,
                MysticQuestsTheme.cardButtonStyle(selected),
                uiText(entry.displayName()),
                selected ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_PRIMARY,
                uiText(entry.questId()),
                MysticQuestsTheme.TEXT_MUTED,
                uiText(progressSummary(entry)),
                MysticQuestsTheme.TEXT_SECONDARY);
    }

    private String progressSummary(JournalEntry entry) {
        int objectives = entry.objectives().size();
        return objectives == 1 ? "1 objective" : objectives + " objectives";
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


    public static final class PageEventData {
        private String quest;
        private String action;


        public String quest() {
            return quest == null ? "" : quest;
        }

        public void setQuest(String quest) {
            this.quest = quest;
        }

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
