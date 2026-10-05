package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.server.core.ui.Value;

import java.util.Locale;

/**
 * Named styles from {@code mysticquests/Theme.ui} that a page swaps at runtime.
 *
 * <p>The client lets the server set an element's {@code Style} to a reference into a document, the
 * way the engine's own pages mark the selected row ({@code WorldEventPanelPage} sets
 * {@code #ListContainer[i] #SelectButton.Style} to {@code Value.ref(LIST_ROW, "SelectedRowStyle")}).
 * Selection and badge colour therefore never need a runtime texture change, which the client
 * refuses with a disconnect.
 */
public final class UiStyles {
    public static final String THEME = "mysticquests/Theme.ui";

    public static final Value<String> NAV = Value.ref(THEME, "NavStyle");
    public static final Value<String> NAV_SELECTED = Value.ref(THEME, "NavSelectedStyle");
    public static final Value<String> NAV_ROW = Value.ref(THEME, "NavRowStyle");
    public static final Value<String> NAV_ROW_SELECTED = Value.ref(THEME, "NavRowSelectedStyle");
    public static final Value<String> CARD = Value.ref(THEME, "CardStyle");
    public static final Value<String> CARD_SELECTED = Value.ref(THEME, "CardSelectedStyle");
    public static final Value<String> CARD_STATIC = Value.ref(THEME, "CardStaticStyle");
    public static final Value<String> BUTTON_PRIMARY = Value.ref(THEME, "PrimaryButtonStyle");
    public static final Value<String> BUTTON_SECONDARY = Value.ref(THEME, "SecondaryButtonStyle");
    public static final Value<String> BUTTON_SELECTED = Value.ref(THEME, "SelectedButtonStyle");
    public static final Value<String> BUTTON_DANGER = Value.ref(THEME, "DangerButtonStyle");
    public static final Value<String> SMALL_PRIMARY = Value.ref(THEME, "SmallPrimaryButtonStyle");
    public static final Value<String> SMALL_SECONDARY = Value.ref(THEME, "SmallSecondaryButtonStyle");
    public static final Value<String> SMALL_DANGER = Value.ref(THEME, "SmallDangerButtonStyle");
    public static final Value<String> CHIP = Value.ref(THEME, "ChipStyle");
    public static final Value<String> CHIP_SELECTED = Value.ref(THEME, "ChipSelectedStyle");

    /** Badge colours (Redesign Bible §4.1): one meaning each, always with a word beside it. */
    public enum Tone {
        PURPLE("BadgePurple", "BoardFramePurple"),
        GOLD("BadgeGold", "BoardFrameGold"),
        CYAN("BadgeCyan", "BoardFrameCyan"),
        GREEN("BadgeGreen", "BoardFrameGreen"),
        RED("BadgeRed", "BoardFrameRed"),
        NEUTRAL("BadgeNeutral", "BoardFrameNeutral"),
        GOLD_OUTLINE("BadgeGoldOutline", "BoardFrameGold");

        private final Value<String> style;
        private final Value<String> frame;

        Tone(String badge, String frame) {
            this.style = Value.ref(THEME, badge);
            this.frame = Value.ref(THEME, frame);
        }

        /** The badge style in this colour. */
        public Value<String> style() {
            return style;
        }

        /** The quest board's card frame in this colour. */
        public Value<String> frame() {
            return frame;
        }
    }

    private UiStyles() {
    }

    /**
     * A category's badge colour: story is narrative purple, exploration and side work guidance cyan,
     * contracts and community gold, dungeons red, guilds green, anything else purple.
     */
    public static Tone categoryTone(String category) {
        String key = category == null ? "" : category.toLowerCase(Locale.ROOT);
        return switch (key) {
            case "side", "exploration" -> Tone.CYAN;
            case "contract", "community", "daily", "event" -> Tone.GOLD;
            case "dungeon", "raid" -> Tone.RED;
            case "guild" -> Tone.GREEN;
            default -> Tone.PURPLE;
        };
    }

    /** The meter colour that goes with a category's badge. */
    public static String categoryColour(String category) {
        return switch (categoryTone(category)) {
            case CYAN -> UiText.CYAN;
            case GOLD, GOLD_OUTLINE -> UiText.GOLD;
            case RED -> UiText.RED;
            case GREEN -> UiText.GREEN;
            default -> UiText.PURPLE;
        };
    }
}
