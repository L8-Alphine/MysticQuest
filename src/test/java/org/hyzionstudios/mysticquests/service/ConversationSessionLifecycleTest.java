package org.hyzionstudios.mysticquests.service;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * A conversation must end when its page closes, and must never be restarted underneath itself.
 *
 * <p>Three entry points can see the same key press: {@code PlayerMouseButtonEvent},
 * {@code PlayerInteractEvent}, and the {@code OpenCustomUIInteraction} page supplier. The first two
 * refuse to start a second conversation while one is running; the supplier did not, so it rewound
 * the dialogue to its opening node and ran that node's events again — the repeat players saw.
 *
 * <p>The other half of the same bug: a page closed by the client only tells the server through
 * {@code onDismiss}. Without that hook the session outlived the page, {@code inConversation} stayed
 * true for the rest of the login, and the guards above then swallowed every further interaction.
 *
 * <p>Both need a live client to reproduce, so they are guarded here at the source level, in the
 * same style as {@link org.hyzionstudios.mysticquests.ui.QuestHudJoinTimingTest}.
 */
final class ConversationSessionLifecycleTest {
    private static final Path SERVICE =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/service/ConversationService.java");
    private static final Path PAGE =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/ui/ConversationPage.java");

    @Test
    void closingThePageEndsTheConversation() throws IOException {
        String page = Files.readString(PAGE);
        assertTrue(page.contains("public void onDismiss("),
                "ConversationPage must hear about a client-side close");
        assertTrue(page.contains("conversationService.pageDismissed(playerId, sessionToken)"),
                "A dismissed page must drop its session, or the player stays stuck in conversation");
    }

    @Test
    void theInteractionSupplierResumesALiveConversationRatherThanRestartingIt() throws IOException {
        String service = Files.readString(SERVICE);
        int supplier = service.indexOf("public CustomUIPage tryCreateInteractionPage(");
        assertTrue(supplier >= 0, "tryCreateInteractionPage is the third entry point and must exist");
        int adopt = service.indexOf("adoptSession(playerRef)", supplier);
        int start = service.indexOf("startSession(", supplier);
        assertTrue(adopt >= 0 && adopt < start,
                "A live session must be adopted before a new one is started, or the dialogue repeats");
    }

    @Test
    void aStalePageDismissalCannotEndTheSessionThatReplacedIt() throws IOException {
        String service = Files.readString(SERVICE);
        assertTrue(service.contains("session.token() != token"),
                "Replacing a page dismisses the old one; that must not end the new page's conversation");
        assertTrue(service.contains("session.ownedBy(token)"),
                "Adopting a session must transfer ownership to the incoming page");
    }

    @Test
    void choicesUseServerIssuedOpaqueTokensAndTranscriptCanBeToggled() throws IOException {
        String service = Files.readString(SERVICE);
        String page = Files.readString(PAGE);
        assertTrue(service.contains("UUID.randomUUID().toString()")
                        && service.contains("choiceTokens.getOrDefault"),
                "Choice events must resolve through server-issued tokens, not client-supplied indexes");
        assertTrue(page.contains("#TranscriptToggle") && page.contains("#TranscriptPanel.Visible"),
                "The cinematic dialogue page must expose its recent transcript");
    }

    @Test
    void loggingOutClearsTheSession() throws IOException {
        String service = Files.readString(SERVICE);
        int unregister = service.indexOf("public void unregisterPlayer(");
        assertTrue(unregister >= 0, "unregisterPlayer is the disconnect path");
        String body = service.substring(unregister, service.indexOf('}', service.indexOf('}', unregister) + 1));
        assertTrue(body.contains("sessions.remove(playerId)"),
                "A player who logs out mid-conversation must come back able to talk again");
    }
}
