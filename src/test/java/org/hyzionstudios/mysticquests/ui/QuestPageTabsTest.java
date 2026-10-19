package org.hyzionstudios.mysticquests.ui;

import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** The tab strip on the quest board and the journal. */
final class QuestPageTabsTest {
    @Test
    void boardAndJournalComeFirstAndTheCurrentPageIsSelected() {
        List<QuestPageTabs.Tab> tabs = QuestPageTabs.tabs(QuestPageTabs.JOURNAL, List.of());

        assertEquals(List.of("Quest Board", "Journal"), tabs.stream().map(QuestPageTabs.Tab::label).toList());
        assertFalse(tabs.get(0).selected());
        assertTrue(tabs.get(1).selected());
    }

    /** With their trackers hidden, these tabs are how players reach the mods' own quest books. */
    @Test
    void eachMirroredModWithABookGetsATabAfterThem() {
        List<QuestPageTabs.Tab> tabs = QuestPageTabs.tabs(QuestPageTabs.BOARD, List.of(
                new JournalSources.ModBook("endgame", "Endgame Quests"),
                new JournalSources.ModBook("ziggfreed", "MMO Quests")));

        assertEquals(List.of("Quest Board", "Journal", "Endgame Quests", "MMO Quests"),
                tabs.stream().map(QuestPageTabs.Tab::label).toList());
        assertEquals("book:endgame", tabs.get(2).id());
        assertEquals("book:ziggfreed", tabs.get(3).id());
        assertTrue(tabs.get(0).selected());
        assertFalse(tabs.get(2).selected() || tabs.get(3).selected());
    }

    @Test
    void bothQuestPagesDeclareTheStripTheTabsFill() throws java.io.IOException {
        for (String page : List.of("QuestMenuPage.ui", "JournalPage.ui")) {
            String ui = java.nio.file.Files.readString(
                    java.nio.file.Path.of("src/main/resources/Common/UI/Custom/mysticquests/Pages", page));
            assertTrue(ui.contains("Group #Tabs {"), page + " must declare #Tabs");
        }
    }
}
