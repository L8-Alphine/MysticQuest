package org.hyzionstudios.mysticquests.ui;

import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.IntStream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class ConversationLayoutTest {

    @Test
    void everyChoiceGetsANumberedLabelWithNoCap() {
        List<String> texts = IntStream.rangeClosed(1, 25).mapToObj(i -> "Reply " + i).toList();

        List<String> labels = ConversationLayout.choiceLabels(texts);

        assertEquals(25, labels.size(), "Nodes may offer more than four replies");
        assertEquals("1.  Reply 1", labels.getFirst());
        assertEquals("5.  Reply 5", labels.get(4));
        assertEquals("25.  Reply 25", labels.getLast());
    }

    @Test
    void choiceLabelsAreOneLine() {
        List<String> texts = new ArrayList<>();
        texts.add("I can\nhelp.\r\n");
        texts.add(null);

        List<String> labels = ConversationLayout.choiceLabels(texts);

        assertEquals("1.  I can help.", labels.get(0));
        assertEquals("2.  ", labels.get(1));
    }

    @Test
    void choiceSelectorsAddressEachAppendedRow() {
        assertEquals("#ChoiceList[0] #Choice", ConversationLayout.choiceSelector(0));
        assertEquals("#ChoiceList[11] #Choice", ConversationLayout.choiceSelector(11));
    }

    @Test
    void footerSaysWhenRepliesOverflowTheVisibleRows() {
        assertEquals("Leave to end the conversation.", ConversationLayout.footerHint(0));
        assertEquals("Choose a reply. Leaving ends the conversation.",
                ConversationLayout.footerHint(ConversationLayout.VISIBLE_CHOICES));
        String many = ConversationLayout.footerHint(ConversationLayout.VISIBLE_CHOICES + 3);
        assertTrue(many.startsWith((ConversationLayout.VISIBLE_CHOICES + 3) + " replies"), many);
        assertTrue(many.contains("scroll"), many);
    }

    @Test
    void initialsStandInForAMissingPortrait() {
        assertEquals("VE", ConversationLayout.initials("Village Elder"));
        assertEquals("RO", ConversationLayout.initials("rowan"));
        assertEquals("", ConversationLayout.initials("  "));
        assertEquals("", ConversationLayout.initials(null));
    }
}
