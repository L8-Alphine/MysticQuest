package org.hyzionstudios.mysticquests.ui;

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

import java.util.Optional;
import java.util.UUID;

/**
 * The quest board (Redesign Bible §7.3). {@link QuestBoardModel} decides what shows; this page draws
 * it. Accepting always goes through {@link PlayerQuestService#startQuest}, which checks again whether
 * the quest can start, so a stale board can never start a quest the player no longer qualifies for.
 */
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
        QuestBoardModel board = new QuestBoardModel(questService.availableJournal(playerId), questService.lockedJournal(playerId),
                questService::definition);
        builder.set("#AvailableCount.Text", Integer.toString(board.availableCount()));

        if (board.isEmpty()) {
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

        Optional<QuestBoardModel.Card> selected = board.select(selectedQuestId);
        selectedQuestId = selected.map(card -> card.entry().questId()).orElse(null);

        int head = 0;
        int index = 0;
        for (QuestBoardModel.Section section : board.sections()) {
            builder.appendInline("#QuestCardList", sectionHeader("BoardHead" + head++, section.title()));
            for (QuestBoardModel.Card card : section.cards()) {
                String cardId = "QuestCard" + index++;
                builder.appendInline("#QuestCardList", questCard(cardId, card.entry().questId().equals(selectedQuestId), card));
                eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#" + cardId,
                        EventData.of("Quest", card.entry().questId()));
            }
        }

        selected.ifPresent(card -> renderPreview(builder, eventBuilder, card));
    }

    private void renderPreview(UICommandBuilder builder, UIEventBuilder eventBuilder, QuestBoardModel.Card card) {
        builder.set("#PreviewCategory.Text", QuestCategories.badge(card.category()));
        builder.set("#PreviewAvailability.Text", card.locked() ? "LOCKED" : "AVAILABLE");
        builder.set("#PreviewQuestName.Text", card.entry().displayName());
        builder.set("#PreviewQuestDescription.Text", card.entry().description());
        int row = 0;
        for (QuestBoardModel.Fact fact : card.facts()) {
            builder.appendInline("#PreviewFacts", """
                    Group #PreviewFact%d {
                      LayoutMode: Left;
                      Anchor: (Bottom: 6);

                      Label #PreviewFact%dLabel {
                        Text: "%s";
                        Style: (FontSize: 10, RenderBold: true, TextColor: %s);
                        Anchor: (Width: 92);
                      }

                      Label #PreviewFact%dValue {
                        Text: "%s";
                        Style: (FontSize: 13, TextColor: %s, Wrap: true);
                        FlexWeight: 1;
                      }
                    }
                    """.formatted(row, row, uiText(fact.label()), MysticQuestsTheme.TEXT_MUTED, row, uiText(fact.value()),
                    MysticQuestsTheme.TEXT_SECONDARY));
            row++;
        }
        if (card.locked()) {
            builder.set("#PreviewNotice.Text", card.lockedText());
            builder.set("#AcceptButton.Visible", false);
            return;
        }
        builder.set("#PreviewNotice.Text", statusMessage);
        builder.set("#AcceptButton.Visible", true);
        eventBuilder.addEventBinding(CustomUIEventBindingType.Activating, "#AcceptButton", EventData.of("Action", "accept"));
    }

    private String sectionHeader(String headerId, String title) {
        return """
                Label #%s {
                  Text: "%s";
                  Style: (FontSize: 10, RenderBold: true, LetterSpacing: 1, TextColor: %s);
                  Anchor: (Height: 24, Top: 6);
                }
                """.formatted(headerId, uiText(title), MysticQuestsTheme.TEXT_MUTED);
    }

    private String questCard(String id, boolean selected, QuestBoardModel.Card card) {
        String badgeColor = card.locked() ? MysticQuestsTheme.PANEL_RAISED : MysticQuestsTheme.NARRATIVE_PURPLE;
        String titleColor = card.locked() ? MysticQuestsTheme.TEXT_MUTED
                : selected ? MysticQuestsTheme.ACCENT_GOLD : MysticQuestsTheme.TEXT_PRIMARY;
        String detail = card.locked() ? card.lockedText() : card.meta();
        return """
                Button #%s {
                  Anchor: (Height: 84, Bottom: 6);
                  LayoutMode: Top;
                  Style: %s;
                  Padding: (Horizontal: 14, Top: 9, Bottom: 8);

                  Group {
                    Anchor: (Height: 20, Bottom: 6);
                    LayoutMode: Left;

                    Group {
                      Anchor: (Width: 96, Height: 20);
                      Background: %s;
                      Padding: (Horizontal: 8);

                      Label #%sBadge {
                        Text: "%s";
                        Style: (FontSize: 10, RenderBold: true, TextColor: #FFFFFF, HorizontalAlignment: Center, VerticalAlignment: Center);
                      }
                    }
                  }

                  Label #%sName {
                    Text: "%s";
                    Style: (FontSize: 16, RenderBold: true, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 12);
                    Anchor: (Bottom: 4);
                  }

                  Label #%sMeta {
                    Text: "%s";
                    Style: (FontSize: 12, TextColor: %s, ShrinkTextToFit: true, MinShrinkTextToFitFontSize: 10);
                  }
                }
                """.formatted(
                id,
                MysticQuestsTheme.cardButtonStyle(selected),
                badgeColor,
                id, card.locked() ? "LOCKED" : uiText(QuestCategories.badge(card.category())),
                id, uiText(card.entry().displayName()), titleColor,
                id, uiText(detail), card.locked() ? MysticQuestsTheme.ACCENT_BLUE : MysticQuestsTheme.TEXT_SECONDARY);
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
