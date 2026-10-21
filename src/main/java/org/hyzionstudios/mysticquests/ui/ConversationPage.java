package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.ConversationService.ConversationOption;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.List;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Cinematic dialogue (Redesign Bible §6.5): speaker, line, voice state, choices and transcript in one
 * band along the bottom of the screen. Choices carry server-issued tokens that are re-issued on every
 * render, so a forged or stale choice selects nothing.
 */
public final class ConversationPage extends MysticQuestsPage<ConversationPage.PageEventData> {
    static final String DOCUMENT = "mysticquests/Pages/ConversationPage.ui";
    static final String CHOICE_ROW = "mysticquests/Rows/ConversationChoiceRow.ui";
    private static final int TRANSCRIPT_LINES = 12;

    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .append(new KeyedCodec<>("Choice", Codec.STRING), PageEventData::setChoice, PageEventData::choice).add()
            .append(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action).add()
            .build();

    private final UUID playerId;
    private final long sessionToken;
    private final ConversationService conversationService;
    private boolean transcriptVisible;
    /** The model the card shows live, as of the last render; null for the portrait fallback. */
    private SpeakerStage.Capture liveStage;

    public ConversationPage(PlayerRef playerRef, UUID playerId, long sessionToken, ConversationService conversationService) {
        super(playerRef, EVENT_CODEC);
        this.playerId = playerId;
        this.sessionToken = sessionToken;
        this.conversationService = conversationService;
    }

    /**
     * Ends the conversation when the player closes the page.
     *
     * <p>This lifetime lets the client dismiss the page on its own, and the server hears about it
     * only here. Leaving the session behind would keep the player permanently "in a conversation":
     * the next interaction with the NPC is swallowed by the already-talking guard, and the dialogue
     * would resume — or restart — instead of opening fresh.
     */
    @Override
    public void onDismiss(@Nonnull Ref<EntityStore> playerEntity, @Nonnull Store<EntityStore> store) {
        // Every way the page goes away ends here: closed, replaced, the death screen, a player reset.
        SpeakerPreviewService preview = conversationService.livePreview();
        if (preview != null) {
            preview.hide(playerRef, playerEntity, store);
        }
        conversationService.pageDismissed(playerId, sessionToken);
    }

    /** Sends the speaker's model to the card once the page is on its way to the client. */
    @Override
    protected void onBuilt(Ref<EntityStore> ref, Store<EntityStore> store) {
        // Not view(): that re-issues the choice tokens render() has just bound.
        SpeakerPreviewService preview = conversationService.livePreview();
        if (preview != null && liveStage != null) {
            preview.show(playerRef, ref, store, this, liveStage, true);
        }
    }

    @Override
    protected String document() {
        return DOCUMENT;
    }

    @Override
    protected void handle(Ref<EntityStore> ref, Store<EntityStore> store, PageEventData data) {
        switch (data.action()) {
            case "transcript" -> transcriptVisible = !transcriptVisible;
            case "leave" -> closePage();
            default -> {
                if (!data.choice().isBlank() && !conversationService.choose(playerId, data.choice(), ref, store)) {
                    // The scene ended and the service closed the page; nothing more to send.
                    markClosed();
                }
            }
        }
    }

    @Override
    protected void render(UICommandBuilder commands, UIEventBuilder events) {
        events.addEventBinding(CustomUIEventBindingType.Activating, "#TranscriptToggle", EventData.of("Action", "transcript"));
        events.addEventBinding(CustomUIEventBindingType.Activating, "#LeaveButton", EventData.of("Action", "leave"));
        commands.clear(ConversationLayout.CHOICE_LIST);

        ConversationService.ConversationView view = conversationService.view(playerId);
        if (view == null) {
            commands.set("#SpeakerName.Text", "");
            commands.set("#SpeakerInitials.Text", "");
            commands.set("#SpeakerInitials.Visible", true);
            commands.set("#SpeakerImage.Visible", false);
            commands.set("#SpeakerStageWrap.Visible", false);
            commands.set("#PortraitFallback.Visible", true);
            liveStage = null;
            commands.set("#SpeakerTitle.Text", "");
            commands.set("#DialogueText.Text", "");
            commands.set("#VoiceBadge.Visible", false);
            commands.set("#TranscriptPanel.Visible", false);
            commands.set("#FooterHint.Text", "This conversation has ended.");
            return;
        }
        commands.set("#SpeakerName.Text", UiText.oneLine(view.speaker()));
        portrait(commands, view);
        commands.set("#SpeakerTitle.Text", UiText.oneLine(view.speaker()));
        commands.set("#DialogueText.Text", UiText.safe(view.text()));
        commands.set("#VoiceBadge.Visible", view.voiced());
        commands.set("#TranscriptPanel.Visible", transcriptVisible);
        commands.set("#TranscriptText.Text", transcript(view.transcript()));
        commands.set("#TranscriptToggle.Text", transcriptVisible ? "Hide transcript" : "Transcript");

        // One row per choice, however many the node offers: #ChoiceList scrolls.
        List<ConversationOption> choices = view.choices();
        List<String> labels = ConversationLayout.choiceLabels(
                choices.stream().map(ConversationOption::text).toList());
        commands.set("#FooterHint.Text", ConversationLayout.footerHint(choices.size()));
        for (int index = 0; index < choices.size(); index++) {
            String selector = ConversationLayout.choiceSelector(index);
            commands.append(ConversationLayout.CHOICE_LIST, CHOICE_ROW);
            commands.set(selector + ".Text", labels.get(index));
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    EventData.of("Choice", choices.get(index).token()));
        }
    }

    /**
     * The NPC's model portrait in the character card, as the vanilla Memories page shows an NPC
     * ({@code AssetImage.AssetPath}); the speaker's initials when it has none.
     */
    private void portrait(UICommandBuilder commands, ConversationService.ConversationView view) {
        SpeakerPreviewService preview = conversationService.livePreview();
        boolean live = preview != null && preview.enabled() && view.stage() != null;
        liveStage = live ? view.stage() : null;
        commands.set("#SpeakerStageWrap.Visible", live);
        commands.set("#PortraitFallback.Visible", !live);
        boolean hasPortrait = view.portrait() != null && !view.portrait().isBlank();
        if (hasPortrait) {
            commands.set("#SpeakerImage.AssetPath", view.portrait());
        }
        commands.set("#SpeakerImage.Visible", hasPortrait);
        commands.set("#SpeakerInitials.Visible", !hasPortrait);
        commands.set("#SpeakerInitials.Text", ConversationLayout.initials(view.speaker()));
    }

    private static String transcript(List<ConversationService.TranscriptLine> lines) {
        int from = Math.max(0, lines.size() - TRANSCRIPT_LINES);
        return lines.subList(from, lines.size()).stream()
                .map(line -> line.speaker() + ":  " + line.text())
                .collect(Collectors.joining("\n\n"));
    }

    public static final class PageEventData {
        private String choice;
        private String action;

        public String choice() {
            return choice == null ? "" : choice;
        }

        public void setChoice(String choice) {
            this.choice = choice;
        }

        public String action() {
            return action == null ? "" : action;
        }

        public void setAction(String action) {
            this.action = action;
        }
    }
}
