package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestResult;

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
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

/**
 * The quest board (Redesign Bible §7.3). {@link QuestBoardModel} decides what shows; this page draws
 * it as category-coloured cards in two columns with a filter chip per category. Accepting always goes
 * through {@link PlayerQuestService#startQuest}, which checks again whether the quest can start, so a
 * stale board can never start a quest the player no longer qualifies for.
 */
public final class QuestMenuPage extends MysticQuestsPage<QuestMenuPage.PageEventData> {
    static final String DOCUMENT = "mysticquests/Pages/QuestMenuPage.ui";
    static final String FILTER_CHIP = "mysticquests/Rows/BoardFilterChip.ui";
    static final String CARD_ROW = "mysticquests/Rows/BoardCardRow.ui";
    private static final String ALL = "ALL";

    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .append(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action).add()
            .append(new KeyedCodec<>("Id", Codec.STRING), PageEventData::setId, PageEventData::id).add()
            .build();

    private final UUID playerId;
    private final PlayerQuestService questService;
    private String filter = ALL;
    /** Feedback from the last accept attempt, shown until the next action. */
    @Nullable
    private Message status;

    public QuestMenuPage(PlayerRef playerRef, UUID playerId, PlayerQuestService questService) {
        super(playerRef, EVENT_CODEC);
        this.playerId = playerId;
        this.questService = questService;
    }

    @Override
    protected String document() {
        return DOCUMENT;
    }

    @Override
    protected void handle(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        status = null;
        switch (data.action()) {
            case "close" -> closePage();
            case "filter" -> filter = data.id().isBlank() ? ALL : data.id();
            case "accept" -> {
                QuestResult result = questService.startQuest(playerId, data.id());
                status = UiText.status(result.message(), result.success());
            }
            default -> {
                // An action from an older page; the refresh shows the current board.
            }
        }
    }

    @Override
    protected void onFailure(RuntimeException failure) {
        status = UiText.of("That did not work. Try again, or tell staff if it keeps happening.", UiText.RED);
    }

    @Override
    protected void render(UICommandBuilder commands, UIEventBuilder events) {
        QuestBoardModel board = new QuestBoardModel(questService.availableJournal(playerId), questService.lockedJournal(playerId),
                questService::definition);
        commands.set("#HeaderSummary.Text", board.availableCount() == 1 ? "1 available" : board.availableCount() + " available");
        events.addEventBinding(CustomUIEventBindingType.Activating, "#CloseButton", EventData.of("Action", "close"));
        commands.set("#StatusText.TextSpans", status == null
                ? UiText.muted(board.isEmpty() ? "" : "Pick a quest to accept it. Your journal keeps the ones you have taken on.")
                : status);

        boolean empty = board.isEmpty();
        commands.set("#BoardEmpty.Visible", empty);
        commands.set("#CardRows.Visible", !empty);
        commands.set("#FilterBar.Visible", !empty);
        commands.clear("#FilterBar");
        commands.clear("#CardRows");
        if (empty) {
            commands.set("#EmptyBody.Text", "Explore the world or speak with a quest giver. Quests you have already taken on are in your journal.");
            return;
        }

        if (!filter.equals(ALL) && board.sections().stream().noneMatch(section -> section.title().equals(filter))) {
            filter = ALL;
        }
        List<String> chips = new ArrayList<>();
        chips.add(ALL);
        board.sections().forEach(section -> chips.add(section.title()));
        for (int index = 0; index < chips.size(); index++) {
            String chip = chips.get(index);
            String selector = "#FilterBar[" + index + "]";
            commands.append("#FilterBar", FILTER_CHIP);
            commands.set(selector + ".Text", chip.equals(ALL) ? "All" : chipLabel(chip));
            commands.set(selector + ".Style", chip.equals(filter) ? UiStyles.CHIP_SELECTED : UiStyles.CHIP);
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    new EventData().append("Action", "filter").append("Id", chip));
        }

        List<QuestBoardModel.Card> cards = new ArrayList<>();
        for (QuestBoardModel.Section section : board.sections()) {
            if (filter.equals(ALL) || section.title().equals(filter)) {
                cards.addAll(section.cards());
            }
        }
        for (int index = 0; index < cards.size(); index += 2) {
            String row = "#CardRows[" + (index / 2) + "]";
            commands.append("#CardRows", CARD_ROW);
            renderCard(commands, events, row, "Left", cards.get(index));
            boolean hasRight = index + 1 < cards.size();
            commands.set(row + " #RightCard.Visible", hasRight);
            if (hasRight) {
                renderCard(commands, events, row, "Right", cards.get(index + 1));
            }
        }
    }

    private void renderCard(UICommandBuilder commands, UIEventBuilder events, String row, String side, QuestBoardModel.Card card) {
        String at = row + " #" + side;
        UiStyles.Tone tone = card.locked() ? UiStyles.Tone.NEUTRAL : UiStyles.categoryTone(card.category());
        commands.set(at + "Frame.Style", tone.frame());
        commands.set(at + "Badge.Text", card.locked() ? "Locked" : QuestCategories.badge(card.category()));
        commands.set(at + "Badge.Style", tone.style());
        commands.set(at + "Title.Text", UiText.oneLine(card.entry().displayName()));
        commands.set(at + "Meta.Text", meta(card));
        commands.set(at + "Description.Text", UiText.oneLine(card.locked() && !card.lockedText().isBlank()
                ? card.lockedText() : card.entry().description()));

        commands.set(at + "FactOne.TextSpans", UiText.pair("Party     ", UiText.MUTED,
                card.partySize() > 0 ? "Best with " + card.partySize() + " players" : "Solo", UiText.TEXT));
        commands.set(at + "FactTwo.TextSpans", card.rewardText().isBlank()
                ? UiText.pair("Steps     ", UiText.MUTED, steps(card), UiText.TEXT)
                : UiText.pair("Reward    ", UiText.MUTED, UiText.oneLine(card.rewardText()), UiText.GOLD));

        if (card.locked()) {
            commands.set(at + "Accept.Text", "Locked");
            commands.set(at + "Accept.Style", UiStyles.BUTTON_SECONDARY);
            commands.set(at + "Accept.Disabled", true);
            return;
        }
        commands.set(at + "Accept.Text", "Accept");
        commands.set(at + "Accept.Style", UiStyles.BUTTON_PRIMARY);
        commands.set(at + "Accept.Disabled", false);
        events.addEventBinding(CustomUIEventBindingType.Activating, at + "Accept",
                new EventData().append("Action", "accept").append("Id", card.entry().questId()));
    }

    /** "Story  -  Hard": the category in words, then difficulty when the author gave one. */
    private static String meta(QuestBoardModel.Card card) {
        String category = chipLabel(QuestCategories.section(card.category()));
        return card.difficulty().isBlank() ? category : category + "  -  " + card.difficulty();
    }

    private static String steps(QuestBoardModel.Card card) {
        int objectives = card.entry().objectives().size();
        int steps = card.entry().grouped() ? card.entry().stages().size() : 0;
        return (objectives == 1 ? "1 objective" : objectives + " objectives") + (steps > 1 ? " in " + steps + " steps" : "");
    }

    /** "STORY QUESTS" as "Story quests" for chips and meta lines. */
    private static String chipLabel(String title) {
        if (title == null || title.isEmpty()) {
            return "";
        }
        String lower = title.toLowerCase(Locale.ROOT);
        return Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    public static final class PageEventData {
        private String action;
        private String id;

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }

        public String id() {
            return id == null ? "" : id;
        }

        public void setId(String id) {
            this.id = id;
        }
    }
}
