package org.hyzionstudios.mysticquests.ui;

import java.util.List;
import java.util.Locale;

/**
 * Quest categories as the Journal rail and the HUD badge show them (Redesign Bible §7.1): the
 * well-known ones in a fixed order, then any category an author named, in the order met.
 */
public final class QuestCategories {
    /** Rail order for the well-known categories; the empty category is a quest with none named. */
    public static final List<String> ORDER = List.of("story", "", "side", "contract", "guild", "community", "daily");

    private QuestCategories() {
    }

    /** Badge text, short enough for the HUD's badge. */
    public static String badge(String category) {
        String label = switch (category) {
            case "" -> "QUEST";
            case "story" -> "STORY";
            case "side" -> "SIDE";
            case "contract" -> "CONTRACT";
            case "guild" -> "GUILD";
            case "community" -> "COMMUNITY";
            case "daily" -> "DAILY";
            default -> category.toUpperCase(Locale.ROOT);
        };
        return label.length() > 10 ? label.substring(0, 10) : label;
    }

    /** Rail section heading. */
    public static String section(String category) {
        return switch (category) {
            case "" -> "QUESTS";
            case "story" -> "STORY QUESTS";
            case "side" -> "SIDE QUESTS";
            case "contract" -> "CONTRACTS";
            case "guild" -> "GUILD";
            case "community" -> "COMMUNITY";
            case "daily" -> "DAILY";
            default -> category.toUpperCase(Locale.ROOT);
        };
    }

    /** Sort key: well-known categories first, in rail order. */
    public static int rank(String category) {
        int index = ORDER.indexOf(category);
        return index < 0 ? ORDER.size() : index;
    }
}
