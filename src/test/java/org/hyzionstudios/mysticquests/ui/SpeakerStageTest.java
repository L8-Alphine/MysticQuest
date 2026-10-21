package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.protocol.Model;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotSame;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;

final class SpeakerStageTest {

    @Test
    void thePreviewIsTheModelPlusTwoInvisibleSpecksAtTheFrameCorners() {
        Model model = new Model();
        model.assetId = "Kweebec_Sapling";
        model.scale = 1.02f;
        SpeakerFraming.Frame frame = new SpeakerFraming.Frame(0.5f, 0.32f, 0.9f);

        BuilderToolPrefabPreview packet = SpeakerStage.packet(new SpeakerStage.Capture(model, frame));

        assertNull(packet.blocksChange, "Any block would make the client frame the blocks alone");
        assertEquals(SpeakerStage.TILT, packet.tilt);
        assertEquals(SpeakerStage.SPIN_SPEED, packet.spinSpeed);
        assertEquals(SpeakerStage.PREVIEW_SCALE, packet.previewScale);
        ClipboardEntityChange[] entities = packet.entityChanges;
        assertEquals(3, entities.length);

        assertSame(model, entities[0].model);
        assertEquals(1.02f, entities[0].model.scale, "The NPC keeps its own scale");
        assertEquals(0f, entities[0].y);

        Model ghost = entities[1].model;
        assertNotSame(model, ghost, "The shared model is never changed");
        assertEquals(SpeakerStage.GHOST_SCALE, ghost.scale);
        assertNull(ghost.particles);
        assertEquals(-0.5f, entities[1].x);
        assertEquals(0.9f - 0.32f, entities[1].y, 1e-6f);
        assertEquals(0.5f, entities[2].z);
        assertEquals(0.9f + 0.32f, entities[2].y, 1e-6f);
    }

    @Test
    void clearingSendsAnEmptyPreview() {
        assertEquals(0, SpeakerStage.clear().entityChanges.length);
    }
}
