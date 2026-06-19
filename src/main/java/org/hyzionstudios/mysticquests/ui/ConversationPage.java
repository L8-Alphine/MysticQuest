package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.ConversationChoice;
import org.hyzionstudios.mysticquests.service.ConversationService;

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
    private static final BuilderCodec<PageEventData> EVENT_CODEC = BuilderCodec
            .builder(PageEventData.class, PageEventData::new)
            .addField(new KeyedCodec<>("Choice", Codec.STRING), PageEventData::setChoice, PageEventData::choice)
            .build();

    private final UUID playerId;
    private final ConversationService conversationService;

    public ConversationPage(PlayerRef playerRef, UUID playerId, ConversationService conversationService) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, EVENT_CODEC);
        this.playerId = playerId;
        this.conversationService = conversationService;
    }

    @Override
    public void build(Ref<EntityStore> playerEntity, UICommandBuilder builder, UIEventBuilder eventBuilder, Store<EntityStore> store) {
        render(builder, eventBuilder);
    }

    @Override
    public void handleDataEvent(Ref<EntityStore> playerEntity, Store<EntityStore> store, PageEventData data) {
        int choiceIndex;
        try {
            choiceIndex = Integer.parseInt(data.choice());
        } catch (NumberFormatException exception) {
            return;
        }
        if (conversationService.choose(playerId, choiceIndex, playerEntity, store)) {
            UICommandBuilder builder = new UICommandBuilder();
            UIEventBuilder eventBuilder = new UIEventBuilder();
            render(builder, eventBuilder);
            sendUpdate(builder, eventBuilder, false);
        }
    }

    private void render(UICommandBuilder builder, UIEventBuilder eventBuilder) {
        builder.append("mysticquests/Pages/ConversationPage.ui");
        ConversationService.ConversationView view = conversationService.view(playerId);
        if (view == null) {
            builder.set("#SpeakerName.Text", "MysticQuests");
            builder.set("#DialogueText.Text", "");
            return;
        }
        builder.set("#SpeakerName.Text", view.speaker());
        builder.set("#SpeakerInitials.Text", initials(view.speaker()));
        builder.set("#SpeakerTitle.Text", view.conversationId());
        builder.set("#DialogueText.Text", view.text());
        appendChoices(builder, eventBuilder, view.choices());
    }

    private void appendChoices(UICommandBuilder builder, UIEventBuilder eventBuilder, List<ConversationChoice> choices) {
        for (int index = 0; index < choices.size(); index++) {
            String id = "Choice" + index;
            builder.appendInline("#ChoiceList", choiceButton(id));
            builder.set("#" + id + " #ChoiceText.Text", choices.get(index).text());
            eventBuilder.addEventBinding(
                    CustomUIEventBindingType.Activating,
                    "#" + id,
                    EventData.of("Choice", Integer.toString(index)));
        }
    }

    private String choiceButton(String id) {
        return """
                TextButton #%s {
                  Anchor: (Height: 42, Bottom: 6);
                  Style: (Background: #1E2B3E, TextColor: #EEF3FC, FontSize: 15, HorizontalAlignment: Left);
                  Label #ChoiceText {
                    Text: "";
                    Style: (FontSize: 15, TextColor: #EEF3FC, Wrap: true);
                  }
                }
                """.formatted(id);
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

        public String choice() {
            return choice == null ? "" : choice;
        }

        public void setChoice(String choice) {
            this.choice = choice;
        }
    }
}
