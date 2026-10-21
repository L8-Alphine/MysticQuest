package org.hyzionstudios.mysticquests.ui;

import java.util.ArrayList;
import java.util.List;

/**
 * What the dialogue page shows, worked out without the engine so it can be tested.
 *
 * <p>Every choice the server offers gets its own row in {@code #ChoiceList}, one per line, numbered
 * from 1. There is no cap: the list scrolls, and the footer says so once there are more replies
 * than fit on screen.
 */
final class ConversationLayout {
    /** Rows {@code #ChoiceList} shows without scrolling; must match ConversationPage.ui. */
    static final int VISIBLE_CHOICES = 4;
    static final String CHOICE_LIST = "#ChoiceList";
    static final String CHOICE_BUTTON = " #Choice";

    private ConversationLayout() {
    }

    /** The selector of the button in the {@code index}-th appended choice row. */
    static String choiceSelector(int index) {
        return CHOICE_LIST + "[" + index + "]" + CHOICE_BUTTON;
    }

    /** The button text of every choice, in order: {@code "1.  I can help."}. */
    static List<String> choiceLabels(List<String> choiceTexts) {
        List<String> labels = new ArrayList<>(choiceTexts.size());
        for (int index = 0; index < choiceTexts.size(); index++) {
            labels.add((index + 1) + ".  " + UiText.oneLine(choiceTexts.get(index)).strip());
        }
        return labels;
    }

    /** The footer line for a node with {@code choiceCount} replies. */
    static String footerHint(int choiceCount) {
        if (choiceCount <= 0) {
            return "Leave to end the conversation.";
        }
        if (choiceCount > VISIBLE_CHOICES) {
            return choiceCount + " replies - scroll for more. Leaving ends the conversation.";
        }
        return "Choose a reply. Leaving ends the conversation.";
    }

    /** One or two letters for the card when the NPC has no portrait. */
    static String initials(String text) {
        if (text == null || text.isBlank()) {
            return "";
        }
        String[] parts = text.trim().split("\\s+");
        if (parts.length == 1) {
            return parts[0].substring(0, Math.min(2, parts[0].length())).toUpperCase();
        }
        return (parts[0].substring(0, 1) + parts[1].substring(0, 1)).toUpperCase();
    }
}
