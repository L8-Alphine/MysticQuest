package org.hyzionstudios.mysticquests.integration.external;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.util.MessageUtil;

import javax.annotation.Nullable;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Plain text of a {@link Message} in one player's language, for MysticQuests surfaces that set
 * {@code .Text} rather than pass messages through.
 *
 * <p>The client localises a message itself, so the server never has to; mirrored quests arrive as
 * messages built from the other mod's translation keys, and the Journal and HUD need words. This does
 * what the client would: look the key up in the player's language, fall back to the server default,
 * fill the parameters (nested messages first), then append the children. A key nobody translates
 * reads as the key, which is what the client shows too.
 */
public final class MessageText {
    private MessageText() {
    }

    public static String plain(@Nullable Message message, String language) {
        if (message == null) {
            return "";
        }
        StringBuilder text = new StringBuilder();
        append(text, message.getFormattedMessage(), language);
        return text.toString().strip();
    }

    /**
     * Like {@link #plain}, but empty when the message is a translation key nobody translates, for
     * optional text such as a flavour line that would otherwise read as a raw key.
     */
    public static String optional(@Nullable Message message, String language) {
        if (message == null) {
            return "";
        }
        FormattedMessage formatted = message.getFormattedMessage();
        if (formatted != null && formatted.messageId != null && !formatted.messageId.isBlank()
                && formatted.rawText == null && lookup(formatted.messageId, language) == null) {
            return "";
        }
        return plain(message, language);
    }

    /** A translation key in the player's language, or the key itself when nobody translates it. */
    public static String translate(@Nullable String key, String language) {
        if (key == null || key.isBlank()) {
            return "";
        }
        String translated = lookup(key, language);
        return translated == null ? key : translated;
    }

    private static void append(StringBuilder text, @Nullable FormattedMessage message, String language) {
        if (message == null) {
            return;
        }
        String own = message.rawText;
        if (message.messageId != null && !message.messageId.isBlank()) {
            String translated = lookup(message.messageId, language);
            own = translated != null ? translated : own != null ? own : message.messageId;
        }
        if (own != null) {
            text.append(MessageUtil.formatText(own, message.params, resolved(message.messageParams, language)));
        }
        if (message.children != null) {
            for (FormattedMessage child : message.children) {
                append(text, child, language);
            }
        }
    }

    /** Nested messages as raw text in the player's language, so formatText only has to paste them. */
    @Nullable
    private static Map<String, FormattedMessage> resolved(@Nullable Map<String, FormattedMessage> nested, String language) {
        if (nested == null || nested.isEmpty()) {
            return nested;
        }
        Map<String, FormattedMessage> plain = new LinkedHashMap<>();
        nested.forEach((key, value) -> {
            StringBuilder text = new StringBuilder();
            append(text, value, language);
            FormattedMessage raw = new FormattedMessage();
            raw.rawText = text.toString();
            plain.put(key, raw);
        });
        return plain;
    }

    @Nullable
    private static String lookup(String key, String language) {
        I18nModule i18n = I18nModule.get();
        if (i18n == null) {
            return null;
        }
        String translated = language == null || language.isBlank() ? null : i18n.getMessage(language, key);
        return translated != null ? translated : i18n.getMessage(I18nModule.DEFAULT_LANGUAGE, key);
    }
}
