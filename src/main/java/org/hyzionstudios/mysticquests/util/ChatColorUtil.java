package org.hyzionstudios.mysticquests.util;

import com.hypixel.hytale.server.core.Message;

import java.util.ArrayList;
import java.util.List;

public final class ChatColorUtil {
    private ChatColorUtil() {
    }

    public static Message message(String text, String defaultColor) {
        if (text == null || text.isEmpty()) {
            return Message.empty();
        }
        List<Message> parts = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        Style style = new Style(defaultColor == null || defaultColor.isBlank() ? "#EEF3FC" : defaultColor);
        for (int index = 0; index < text.length(); index++) {
            char character = text.charAt(index);
            if (character == '&' && index + 1 < text.length()) {
                String hex = hexColor(text, index);
                if (hex != null) {
                    flush(parts, current, style);
                    style.color = hex;
                    style.bold = false;
                    style.italic = false;
                    style.monospace = false;
                    index += 7;
                    continue;
                }
                char code = Character.toLowerCase(text.charAt(index + 1));
                if (applyCode(style, code)) {
                    flush(parts, current, style);
                    index++;
                    continue;
                }
            }
            current.append(character);
        }
        flush(parts, current, style);
        if (parts.isEmpty()) {
            return Message.empty();
        }
        return Message.join(parts.toArray(Message[]::new));
    }

    private static void flush(List<Message> parts, StringBuilder current, Style style) {
        if (current.isEmpty()) {
            return;
        }
        Message message = Message.raw(current.toString()).color(style.color);
        message.bold(style.bold);
        message.italic(style.italic);
        message.monospace(style.monospace);
        parts.add(message);
        current.setLength(0);
    }

    private static boolean applyCode(Style style, char code) {
        String color = legacyColor(code);
        if (color != null) {
            style.color = color;
            style.bold = false;
            style.italic = false;
            style.monospace = false;
            return true;
        }
        switch (code) {
            case 'l' -> style.bold = true;
            case 'o' -> style.italic = true;
            case 'm' -> style.monospace = true;
            case 'r' -> {
                style.color = "#EEF3FC";
                style.bold = false;
                style.italic = false;
                style.monospace = false;
            }
            default -> {
                return false;
            }
        }
        return true;
    }

    private static String hexColor(String text, int index) {
        if (index + 7 >= text.length() || text.charAt(index + 1) != '#') {
            return null;
        }
        String value = text.substring(index + 2, index + 8);
        for (int i = 0; i < value.length(); i++) {
            if (Character.digit(value.charAt(i), 16) < 0) {
                return null;
            }
        }
        return "#" + value;
    }

    private static String legacyColor(char code) {
        return switch (code) {
            case '0' -> "#000000";
            case '1' -> "#0000AA";
            case '2' -> "#00AA00";
            case '3' -> "#00AAAA";
            case '4' -> "#AA0000";
            case '5' -> "#AA00AA";
            case '6' -> "#FFAA00";
            case '7' -> "#AAAAAA";
            case '8' -> "#555555";
            case '9' -> "#5555FF";
            case 'a' -> "#55FF55";
            case 'b' -> "#55FFFF";
            case 'c' -> "#FF5555";
            case 'd' -> "#FF55FF";
            case 'e' -> "#FFFF55";
            case 'f' -> "#FFFFFF";
            default -> null;
        };
    }

    private static final class Style {
        private String color;
        private boolean bold;
        private boolean italic;
        private boolean monospace;

        private Style(String color) {
            this.color = color;
        }
    }
}
