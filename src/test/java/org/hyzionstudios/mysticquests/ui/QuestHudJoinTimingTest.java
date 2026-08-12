package org.hyzionstudios.mysticquests.ui;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The quest HUD must not be pushed on the ready tick.
 *
 * <p>A custom HUD append that reaches the client while it is still registering the asset pack's UI
 * documents fails with "Could not find document …" — for a document the client already has — and
 * that failure disconnects the player instead of degrading. The join path therefore goes through
 * {@code reconcileAfterJoin}, and every other reconcile is held until that grace period ends.
 */
final class QuestHudJoinTimingTest {
    private static final Path BRIDGE =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/hytale/HytaleEventBridge.java");
    private static final Path SERVICE =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/ui/QuestHudService.java");

    @Test
    void joinDefersTheHudInsteadOfReconcilingInline() throws IOException {
        String bridge = Files.readString(BRIDGE);
        assertTrue(bridge.contains("hudService.reconcileAfterJoin("),
                "PlayerReadyEvent must defer the HUD through reconcileAfterJoin");
        assertFalse(bridge.contains("hudService.reconcile("),
                "PlayerReadyEvent must not push the HUD on the ready tick");
    }

    @Test
    void reconcileIsHeldWhileAPlayerIsStillJoining() throws IOException {
        String service = Files.readString(SERVICE);
        assertTrue(service.contains("if (joining.contains(playerId))"),
                "Quests started on join fire change events; those reconciles must be held too");
    }
}
