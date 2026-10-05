package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.server.core.Message;

/**
 * Coloured text for the MysticQuests pages, written to a label's {@code .TextSpans}.
 *
 * <p>Text whose colour depends on state goes through here rather than through {@code .Text} plus a
 * style, because a label's {@code Style.TextColor} only applies in the page's first build: on a later
 * update it silently does nothing (MysticRPG found this in game). A span carries its own colour on
 * every update. A span does not inherit its label's colour either, so every span here has one.
 *
 * <p>Never set both {@code .Text} and {@code .TextSpans} on one label; the documents leave the
 * {@code Text} of every span-written label unset.
 *
 * <p>The colours mirror the palette in {@code Common/UI/Custom/mysticquests/Theme.ui} (Redesign
 * Bible §4.1); the server cannot read the client's stylesheet, so {@code UiMarkupTest} keeps the two
 * in step.
 */
public final class UiText {
    public static final String TEXT = "#F2F4F8";
    public static final String TEXT_SOFT = "#C9CFDA";
    public static final String MUTED = "#9AA4B7";
    public static final String DIM = "#6B7487";
    public static final String PURPLE = "#7E5DD3";
    public static final String PURPLE_TEXT = "#B9A2F2";
    public static final String GOLD = "#CCAA58";
    public static final String CYAN = "#46C6C4";
    public static final String GREEN = "#66D395";
    public static final String RED = "#EF6969";

    private UiText() {
    }

    /** One span in one colour. */
    public static Message of(String text, String colour) {
        return Message.empty().insert(Message.raw(safe(text)).color(colour));
    }

    /** One bold span in one colour. */
    public static Message bold(String text, String colour) {
        return Message.empty().insert(Message.raw(safe(text)).color(colour).bold(true));
    }

    public static Message text(String text) {
        return of(text, TEXT);
    }

    public static Message muted(String text) {
        return of(text, MUTED);
    }

    /** A caption and a value in one label, each in its own colour: {@code "Party  2-4 players"}. */
    public static Message pair(String caption, String captionColour, String value, String valueColour) {
        return Message.empty()
                .insert(Message.raw(safe(caption)).color(captionColour))
                .insert(Message.raw(safe(value)).color(valueColour));
    }

    /** A status line: green for something that worked, red for something that did not. */
    public static Message status(String text, boolean ok) {
        return of(text, ok ? GREEN : RED);
    }

    /** Text for a {@code .Text} write: never null. */
    public static String safe(String text) {
        return text == null ? "" : text;
    }

    /** Text for a single-line label: never null, line breaks folded to spaces. */
    public static String oneLine(String text) {
        return safe(text).replace('\r', ' ').replace('\n', ' ');
    }
}
