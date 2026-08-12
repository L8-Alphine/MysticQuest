package org.hyzionstudios.mysticquests.ui;

/**
 * The UI documents this mod appends, and whether the running JAR actually ships them.
 *
 * <p>Appending a document the client cannot resolve does not fail softly — it disconnects the
 * player. Probe builds strip documents on purpose ({@code -PskipUiPack}, {@code -PskipUiMarkup},
 * {@code -PskipUiDocuments}), so every surface checks before it opens rather than trusting the
 * build it happens to be running in.
 */
public final class UiDocuments {
    public static final String JOURNAL = "mysticquests/Pages/JournalPage.ui";
    public static final String QUEST_MENU = "mysticquests/Pages/QuestMenuPage.ui";
    public static final String CONVERSATION = "mysticquests/Pages/ConversationPage.ui";
    public static final String QUEST_STUDIO = "mysticquests/Pages/QuestStudioPage.ui";

    private UiDocuments() {
    }

    /** True when the document is on the classpath, i.e. the client will be able to resolve it. */
    public static boolean isShipped(String document) {
        return UiDocuments.class.getResource("/Common/UI/Custom/" + document) != null;
    }
}
