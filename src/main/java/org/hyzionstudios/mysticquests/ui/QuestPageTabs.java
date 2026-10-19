package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.packets.interface_.CustomUIEventBindingType;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.ui.builder.EventData;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.ArrayList;
import java.util.List;

/**
 * The tab strip under the quest pages' header: the quest board, the journal, and the quest book of
 * each mod whose quests MysticQuests mirrors.
 *
 * <p>Those mods' trackers are hidden while MysticQuests' tracker stands in for them, and with them
 * went the way players found the mods' own books. A mod tab opens that mod's page exactly as its own
 * hotkey or command does; MysticQuests never redraws it.
 */
final class QuestPageTabs {
    static final String ROW = "mysticquests/Rows/QuestTab.ui";
    static final String BOARD = "board";
    static final String JOURNAL = "journal";
    private static final String BOOK = "book:";

    record Tab(String id, String label, boolean selected) {
    }

    /** What a tab press did to the page it was pressed on. */
    enum Outcome {
        /** The page stays open and refreshes. */
        STAYED,
        /** Another page replaced it; it must send nothing more. */
        LEFT,
        /** Nothing could be opened; the page stays and says so. */
        UNAVAILABLE
    }

    private QuestPageTabs() {
    }

    static List<Tab> tabs(String current, List<JournalSources.ModBook> books) {
        List<Tab> tabs = new ArrayList<>();
        tabs.add(new Tab(BOARD, "Quest Board", current.equals(BOARD)));
        tabs.add(new Tab(JOURNAL, "Journal", current.equals(JOURNAL)));
        for (JournalSources.ModBook book : books) {
            tabs.add(new Tab(BOOK + book.id(), book.label(), false));
        }
        return tabs;
    }

    static void render(UICommandBuilder commands, UIEventBuilder events, String current, JournalSources sources) {
        commands.clear("#Tabs");
        List<Tab> tabs = tabs(current, sources.modBooks());
        for (int index = 0; index < tabs.size(); index++) {
            Tab tab = tabs.get(index);
            String selector = "#Tabs[" + index + "]";
            commands.append("#Tabs", ROW);
            commands.set(selector + ".Text", tab.label());
            commands.set(selector + ".Style", tab.selected() ? UiStyles.BUTTON_SELECTED : UiStyles.BUTTON_SECONDARY);
            events.addEventBinding(CustomUIEventBindingType.Activating, selector,
                    new EventData().append("Action", "tab").append("Id", tab.id()));
        }
    }

    /** Opens the tab's page in place of the current one. On the player's world thread, as page events are. */
    static Outcome open(String id, String current, Ref<EntityStore> ref, Store<EntityStore> store, PlayerRef playerRef,
                        PlayerQuestService quests, JournalSources sources) {
        if (id.equals(current)) {
            return Outcome.STAYED;
        }
        if (id.startsWith(BOOK)) {
            return sources.openModBook(ref, store, id.substring(BOOK.length())) ? Outcome.LEFT : Outcome.UNAVAILABLE;
        }
        String document = switch (id) {
            case BOARD -> UiDocuments.QUEST_MENU;
            case JOURNAL -> UiDocuments.JOURNAL;
            default -> null;
        };
        if (document == null) {
            // A tab from an older page; the refresh shows the current ones.
            return Outcome.STAYED;
        }
        Player player = store.getComponent(ref, Player.getComponentType());
        if (player == null || !UiDocuments.isShipped(document)) {
            return Outcome.UNAVAILABLE;
        }
        player.getPageManager().openCustomPage(ref, store, id.equals(BOARD)
                ? new QuestMenuPage(playerRef, playerRef.getUuid(), quests, sources)
                : new MysticQuestJournalPage(playerRef, playerRef.getUuid(), quests, sources));
        return Outcome.LEFT;
    }
}
