package org.hyzionstudios.mysticquests.util;

import com.hypixel.hytale.protocol.FormattedMessage;
import com.hypixel.hytale.server.core.Message;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

/** Legacy and hex colour codes in authored chat, such as a trigger volume's rich message. */
final class ChatColorUtilTest {
    private static final String GREEN = "#55FF55";
    private static final String AQUA = "#55FFFF";
    private static final String GRAY = "#AAAAAA";

    @Test
    void eachRunKeepsTheColourWrittenBeforeIt() {
        List<Message> runs = ChatColorUtil.message(
                "&a&oWelcome to &bHyzion Fountier&7 Alphine&a&o! Explore and more!", "#EEF3FC").getChildren();

        assertEquals(4, runs.size());
        assertRun(runs.get(0), "Welcome to ", GREEN, true);
        assertRun(runs.get(1), "Hyzion Fountier", AQUA, false);
        assertRun(runs.get(2), " Alphine", GRAY, false);
        assertRun(runs.get(3), "! Explore and more!", GREEN, true);
    }

    @Test
    void textBeforeTheFirstCodeUsesTheDefaultColour() {
        List<Message> runs = ChatColorUtil.message("Hello &cworld", "#123456").getChildren();

        assertRun(runs.get(0), "Hello ", "#123456", false);
        assertRun(runs.get(1), "world", "#FF5555", false);
    }

    @Test
    void resetReturnsToTheMessageColourAndHexColoursApplyToWhatFollows() {
        List<Message> runs = ChatColorUtil.message("&lBold&r plain &#12AB34hex", "#EEEEEE").getChildren();

        assertRun(runs.get(0), "Bold", "#EEEEEE", false);
        assertEquals(Boolean.TRUE, format(runs.get(0)).bold);
        assertRun(runs.get(1), " plain ", "#EEEEEE", false);
        assertNotEquals(Boolean.TRUE, format(runs.get(1)).bold);
        assertRun(runs.get(2), "hex", "#12AB34", false);
    }

    @Test
    void anAmpersandThatIsNotACodeStaysInTheText() {
        List<Message> runs = ChatColorUtil.message("Salt & pepper &zok", "#EEEEEE").getChildren();

        assertEquals(1, runs.size());
        assertEquals("Salt & pepper &zok", runs.get(0).getRawText());
    }

    private static void assertRun(Message run, String text, String color, boolean italic) {
        assertEquals(text, run.getRawText());
        assertEquals(color, run.getColor(), "colour of \"" + text + "\"");
        assertEquals(italic, Boolean.TRUE.equals(format(run).italic), "italic of \"" + text + "\"");
    }

    private static FormattedMessage format(Message run) {
        return run.getFormattedMessage();
    }
}
