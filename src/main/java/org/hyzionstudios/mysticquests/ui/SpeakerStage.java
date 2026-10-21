package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

/**
 * The speaking NPC's real model, ready to show live in the dialogue's character card.
 *
 * <p>A server-driven page shows a model through the {@code PrefabPreviewComponent} vanilla's prefab
 * browser uses: the page holds the component and the model travels separately as a
 * {@link BuilderToolPrefabPreview} ({@code PrefabPage#sendPreviewPacket}, with the same tilt, spin
 * and size). The preview carries entities only, no blocks: any block would make the client frame
 * the blocks alone. EndgameQoL's {@code /eg pet} showcase proved the entity-only form; see
 * {@link SpeakerFraming} for the framing.
 *
 * <p>The model is the one the NPC wears in the world, taken from its {@link ModelComponent}: its own
 * scale and random attachments, not a fresh copy of the asset. It is sent static (no animation
 * sets), as the showcase does. An NPC dressed in a player skin is left to the portrait fallback: the
 * skin is a separate component ({@link PlayerSkinComponent}) that a preview entity cannot carry, and
 * without it the model would show bare.
 */
public final class SpeakerStage {
    static final int TILT = 23;
    static final int SPIN_SPEED = 27;
    static final int PREVIEW_SCALE = 100;
    static final float GHOST_SCALE = 0.001f;

    /** One captured model and its framing; compared by identity to tell speakers apart. */
    public static final class Capture {
        private final com.hypixel.hytale.protocol.Model model;
        private final SpeakerFraming.Frame frame;

        Capture(com.hypixel.hytale.protocol.Model model, SpeakerFraming.Frame frame) {
            this.model = model;
            this.frame = frame;
        }

        SpeakerFraming.Frame frame() {
            return frame;
        }
    }

    private SpeakerStage() {
    }

    /**
     * The NPC at {@code ref}'s model, or null when it has none the preview can show. Reads
     * components, so call it where {@code accessor} may be read (the world thread).
     */
    public static Capture capture(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref) {
        if (accessor == null || ref == null || !ref.isValid()) {
            return null;
        }
        try {
            if (accessor.getComponent(ref, PlayerSkinComponent.getComponentType()) != null) {
                return null;
            }
            ModelComponent component = accessor.getComponent(ref, ModelComponent.getComponentType());
            Model model = component == null ? null : component.getModel();
            Box box = model == null ? null : model.getBoundingBox();
            if (box == null) {
                return null;
            }
            // toPacket() hands out a cached packet shared by every viewer: copy before changing it.
            com.hypixel.hytale.protocol.Model shown = new com.hypixel.hytale.protocol.Model(model.toPacket());
            shown.animationSets = null;
            return new Capture(shown, SpeakerFraming.fit(box.width(), box.height(), box.depth(), box.middleY()));
        } catch (RuntimeException | LinkageError unavailable) {
            return null;
        }
    }

    /** The preview packet: the model at the origin and two invisible specks fixing the frame. */
    static BuilderToolPrefabPreview packet(Capture capture) {
        SpeakerFraming.Frame frame = capture.frame();
        com.hypixel.hytale.protocol.Model ghost = new com.hypixel.hytale.protocol.Model(capture.model);
        ghost.scale = GHOST_SCALE;
        ghost.particles = null;
        ghost.trails = null;
        ghost.light = null;
        ghost.hitbox = null;

        BuilderToolPrefabPreview packet = new BuilderToolPrefabPreview();
        packet.tilt = TILT;
        packet.spinSpeed = SPIN_SPEED;
        packet.previewScale = PREVIEW_SCALE;
        packet.biomeTint = 0;
        packet.waterTint = 0;
        packet.entityChanges = new ClipboardEntityChange[]{
                entity(capture.model, 0f, 0f, 0f),
                entity(ghost, -frame.halfWidth(), frame.midHeight() - frame.halfHeight(), -frame.halfWidth()),
                entity(ghost, frame.halfWidth(), frame.midHeight() + frame.halfHeight(), frame.halfWidth())};
        return packet;
    }

    /** An empty preview, which clears the component. */
    static BuilderToolPrefabPreview clear() {
        BuilderToolPrefabPreview packet = new BuilderToolPrefabPreview();
        packet.entityChanges = new ClipboardEntityChange[0];
        return packet;
    }

    private static ClipboardEntityChange entity(com.hypixel.hytale.protocol.Model model, float x, float y, float z) {
        ClipboardEntityChange entity = new ClipboardEntityChange();
        entity.x = x;
        entity.y = y;
        entity.z = z;
        entity.model = model;
        entity.bodyOrientation = new Direction(0f, 0f, 0f);
        entity.lookOrientation = new Direction(0f, 0f, 0f);
        return entity;
    }
}
