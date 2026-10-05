package org.hyzionstudios.mysticquests.ui;

/**
 * Java-side mirror of the colour tokens in
 * {@code Common/UI/Custom/mysticquests/Theme.ui}.
 *
 * <p>Rows appended at runtime with {@code UICommandBuilder.appendInline} are parsed without the
 * enclosing document's imports, so they cannot reference {@code $MQ.@AccentGold} and must inline the
 * literal value. Keep these constants in sync with {@code Theme.ui} — that file remains the source
 * of truth for anything a {@code .ui} document can reference directly.
 */
public final class MysticQuestsTheme {
    public static final String TRANSPARENT = "#000000(0)";

    public static final String PANEL_RAISED = "#243353";
    public static final String PANEL_SOFT = "#1D2944";

    public static final String TEXT_PRIMARY = "#FFF4D9";
    public static final String TEXT_SECONDARY = "#C6D2E5";
    public static final String TEXT_MUTED = "#7E91AD";

    public static final String PANEL = "#18233B";
    public static final String BORDER = "#34496E";

    public static final String ACCENT_GOLD = "#FFC857";
    public static final String ACCENT_GREEN = "#52D58B";
    public static final String ACCENT_ORANGE = "#F09A45";
    public static final String ACCENT_BLUE = "#39D5D2";

    /** 2.0 palette (Redesign Bible §4.1): story and narrative identity. Mirrors {@code @NarrativePurple}. */
    public static final String NARRATIVE_PURPLE = "#7E5DD3";

    /**
     * Per-state {@code ButtonStyle} body for a clickable container.
     *
     * <p>{@code Button} accepts only a per-state {@code Background}; a flat
     * {@code Style: (Background: …, TextColor: …)} is a markup error, and {@code TextButton} is not a
     * container — it renders its own {@code Text} through a per-state {@code LabelStyle}. Content
     * rows therefore use {@code Button} with child labels and this style body.
     */
    public static String buttonStyle(String background, String hovered, String pressed) {
        return """
                (
                    Default: (Background: %s),
                    Hovered: (Background: %s),
                    Pressed: (Background: %s),
                    Disabled: (Background: %s)
                  )""".formatted(background, hovered, pressed, background);
    }

    /** Style body for a selectable row: the selected row stays lit in every state. */
    public static String rowButtonStyle(boolean selected) {
        return selected
                ? buttonStyle(PANEL_RAISED, PANEL_RAISED, PANEL_RAISED)
                : buttonStyle(TRANSPARENT, PANEL_SOFT, PANEL_RAISED);
    }

    /** Style body for a selectable quest card. */
    public static String cardButtonStyle(boolean selected) {
        return selected
                ? buttonStyle(PANEL_RAISED, PANEL_RAISED, PANEL_RAISED)
                : buttonStyle(PANEL_SOFT, PANEL_RAISED, PANEL);
    }

    private MysticQuestsTheme() {
    }
}
