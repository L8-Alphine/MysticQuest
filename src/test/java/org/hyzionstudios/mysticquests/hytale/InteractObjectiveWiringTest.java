package org.hyzionstudios.mysticquests.hytale;

import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Interaction objectives must come from the engine's use events. {@code PlayerInteractEvent} is
 * deprecated and never raised, so objectives fed from it never counted — the "block interaction does
 * nothing" report. Needs a live client to reproduce, so it is guarded at the source level.
 */
final class InteractObjectiveWiringTest {
    private static final Path SYSTEMS =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/hytale/InteractObjectiveSystems.java");
    private static final Path BRIDGE =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/hytale/HytaleEventBridge.java");
    private static final Path PLUGIN =
            Path.of("src/main/java/org/hyzionstudios/mysticquests/MysticquestsPlugin.java");

    @Test
    void objectivesListenToTheUseEventsTheEngineRaises() throws IOException {
        String systems = Files.readString(SYSTEMS);
        assertTrue(systems.contains("super(UseBlockEvent.Post.class)"), "interactObject must hear block uses");
        assertTrue(systems.contains("super(UseEntityEvent.Post.class)"), "interactEntity must hear entity uses");
        assertTrue(systems.contains("event.getBlockType()"), "the used block's id comes from the event itself");
        assertTrue(systems.contains("getDefaultStateKey()"), "a block in an interaction state answers to its base id");
    }

    @Test
    void bothSystemsAreRegisteredDuringSetup() throws IOException {
        String plugin = Files.readString(PLUGIN);
        assertTrue(plugin.contains("new InteractObjectiveSystems.UseBlock("));
        assertTrue(plugin.contains("new InteractObjectiveSystems.UseEntity("));
    }

    @Test
    void theDeprecatedEventPublishesNoObjectives() throws IOException {
        String bridge = Files.readString(BRIDGE);
        assertFalse(bridge.contains("\"interactObject\""), "a second source would count uses twice");
        assertFalse(bridge.contains("\"interactEntity\""), "a second source would count uses twice");
    }
}
