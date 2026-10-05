package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.ConversationService.ConversationOption;

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

public final class ConversationPage extends InteractiveCustomUIPage<ConversationPage.PageEventData> {
    private static final int MAX_CHOICES = 8;
    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .addField(new KeyedCodec<>("Choice", Codec.STRING), PageEventData::setChoice, PageEventData::choice)
            .addField(new KeyedCodec<>("Action", Codec.STRING), PageEventData::setAction, PageEventData::action)
            .build();

    private final UUID playerId;
    private final long sessionToken;
    private final ConversationService conversationService;
    private boolean transcriptVisible;

    public ConversationPage(PlayerRef playerRef, UUID playerId, long sessionToken, ConversationService conversationService) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EVENT_CODEC);
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
    public void onDismiss(Ref<EntityStore> playerEntity, Store<EntityStore> store) {
        conversationService.pageDismissed(playerId, sessionToken);
    }

    @Override
    public void build(Ref<EntityStore> playerEntity, UICommandBuilder builder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        builder.append("mysticquests/Pages/ConversationPage.ui");
        renderState(builder, eventBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerEntity, Store<EntityStore> store, PageEventData data) {
        if ("transcript".equals(data.action())) {
            transcriptVisible = !transcriptVisible;
            UICommandBuilder builder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            renderState(builder, eventBuilder);
            sendUpdate(builder, eventBuilder, false);
            return;
        }
        if (data.choice().isBlank()) {
            return;
        }
        if (conversationService.choose(playerId, data.choice(), playerEntity, store)) {
            UICommandBuilder builder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            renderState(builder, eventBuilder);
            sendUpdate(builder, eventBuilder, false);
        }
    }

    private void renderState(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        ConversationService.ConversationView view = conversationService.view(playerId);
        if (view == null) {
            builder.set("#SpeakerName.Text", "MysticQuests");
            builder.set("#SpeakerInitials.Text", "MQ");
            builder.set("#SpeakerTitle.Text", "");
            builder.set("#DialogueText.Text", "");
            builder.set("#VoiceBadge.Visible", false);
            builder.set("#TranscriptPanel.Visible", false);
            appendChoices(builder, eventBuilder, List.of());
            return;
        }
        builder.set("#SpeakerName.Text", view.speaker());
        builder.set("#SpeakerInitials.Text", initials(view.speaker()));
        builder.set("#SpeakerTitle.Text", view.conversationId());
        builder.set("#DialogueText.Text", view.text());
        builder.set("#VoiceBadge.Visible", view.voiced());
        builder.set("#TranscriptPanel.Visible", transcriptVisible);
        builder.set("#TranscriptText.Text", transcript(view.transcript()));
        eventBuilder.addEventBinding(
                CustomUIEventBindingType.Activating,
                "#TranscriptToggle",
                EventData.of("Action", "transcript"));
        appendChoices(builder, eventBuilder, view.choices());
    }

    private void appendChoices(UICommandBuilder builder, UIEventBuilder eventBuilder, List<ConversationOption> choices) {
        int visibleChoices = Math.min(choices.size(), MAX_CHOICES);
        for (int index = 0; index < MAX_CHOICES; index++) {
            String id = "Choice" + index;
            boolean visible = index < visibleChoices;
            builder.set("#" + id + ".Visible", visible);
            builder.set("#ChoiceText" + index + ".Text", visible ? choices.get(index).text() : "");
            if (!visible) {
                continue;
            }
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + id,
                    EventData.of("Choice", choices.get(index).token()));
        }
    }

    private String transcript(List<ConversationService.TranscriptLine> lines) {
        int from = Math.max(0, lines.size() - 6);
        return lines.subList(from, lines.size()).stream()
                .map(line -> line.speaker() + ": " + line.text())
                .collect(java.util.stream.Collectors.joining("\n\n"));
    }

    private String initials(String text) {
        if (text == null || text.isBlank()) {
            return "MQ";
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
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
