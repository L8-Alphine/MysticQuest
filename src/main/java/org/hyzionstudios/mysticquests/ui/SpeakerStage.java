package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.component.ComponentAccessor;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.math.shape.Box;
import com.hypixel.hytale.protocol.Direction;
import com.hypixel.hytale.protocol.ModelAttachment;
import com.hypixel.hytale.protocol.packets.buildertools.BuilderToolPrefabPreview;
import com.hypixel.hytale.protocol.packets.buildertools.ClipboardEntityChange;
import com.hypixel.hytale.server.core.asset.type.model.config.Model;
import com.hypixel.hytale.server.core.cosmetics.CosmeticRegistry;
import com.hypixel.hytale.server.core.cosmetics.CosmeticsModule;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPart;
import com.hypixel.hytale.server.core.cosmetics.PlayerSkinPartTexture;
import com.hypixel.hytale.server.core.modules.entity.component.ModelComponent;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSkinComponent;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.HashMap;
import java.util.Map;
import java.util.function.Consumer;

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
 * sets), as the showcase does. An NPC dressed in a player skin (a {@link PlayerSkinComponent}, as
 * HyCitizens' player-model citizens and players are) wears the plain {@code Player} model and its
 * skin separately; a preview entity carries a model only, so {@link SkinOutfit} turns the skin into
 * that model's texture and attachments from the server's {@link CosmeticRegistry}.
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
     * The NPC at {@code ref}'s model, or null when it has none the preview can show; {@code why}
     * hears which way it went. Reads components, so call it where {@code accessor} may be read (the
     * world thread).
     */
    public static Capture capture(ComponentAccessor<EntityStore> accessor, Ref<EntityStore> ref, Consumer<String> why) {
        if (accessor == null || ref == null) {
            why.accept("no speaker entity (the NPC could not be found in the player's world)");
            return null;
        }
        if (!ref.isValid()) {
            why.accept("the speaker entity is no longer valid");
            return null;
        }
        try {
            ModelComponent component = accessor.getComponent(ref, ModelComponent.getComponentType());
            Model model = component == null ? null : component.getModel();
            if (model == null) {
                why.accept("the speaker entity has no ModelComponent");
                return null;
            }
            Box box = model.getBoundingBox();
            if (box == null) {
                why.accept("model " + model.getModelAssetId() + " has no bounding box to frame");
                return null;
            }
            // toPacket() hands out a cached packet shared by every viewer: copy before changing it.
            com.hypixel.hytale.protocol.Model shown = new com.hypixel.hytale.protocol.Model(model.toPacket());
            shown.animationSets = null;
            PlayerSkinComponent skin = accessor.getComponent(ref, PlayerSkinComponent.getComponentType());
            String look = "model " + model.getModelAssetId();
            if (skin != null) {
                look += dress(shown, skin)
                        ? " dressed in its player skin (" + shown.attachments.length + " parts)"
                        : " (its player skin could not be read; shown undressed)";
            }
            why.accept("live preview of " + look);
            return new Capture(shown, SpeakerFraming.fit(box.width(), box.height(), box.depth(), box.middleY()));
        } catch (RuntimeException | LinkageError unavailable) {
            why.accept("capture failed: " + unavailable);
            return null;
        }
    }

    /** Re-textures and attaches {@code shown} to wear the skin; false when the registry is missing. */
    private static boolean dress(com.hypixel.hytale.protocol.Model shown, PlayerSkinComponent skin) {
        CosmeticsModule cosmetics = CosmeticsModule.get();
        CosmeticRegistry registry = cosmetics == null ? null : cosmetics.getRegistry();
        if (registry == null) {
            return false;
        }
        SkinOutfit.Outfit outfit = SkinOutfit.dress(skin.getPlayerSkin(), (slot, assetId) -> part(registry, slot, assetId));
        if (outfit.body() != null) {
            shown.texture = outfit.body().texture;
            shown.gradientSet = outfit.body().gradientSet;
            shown.gradientId = outfit.body().gradientId;
        }
        shown.attachments = outfit.attachments().toArray(ModelAttachment[]::new);
        return true;
    }

    private static SkinOutfit.Part part(CosmeticRegistry registry, SkinOutfit.Slot slot, String assetId) {
        Map<String, PlayerSkinPart> parts = switch (slot) {
            case BODY -> registry.getBodyCharacteristics();
            case UNDERWEAR -> registry.getUnderwear();
            case FACE -> registry.getFaces();
            case EYES -> registry.getEyes();
            case EARS -> registry.getEars();
            case MOUTH -> registry.getMouths();
            case EYEBROWS -> registry.getEyebrows();
            case FACIAL_HAIR -> registry.getFacialHairs();
            case HAIRCUT -> registry.getHaircuts();
            case PANTS -> registry.getPants();
            case OVERPANTS -> registry.getOverpants();
            case UNDERTOP -> registry.getUndertops();
            case OVERTOP -> registry.getOvertops();
            case SHOES -> registry.getShoes();
            case GLOVES -> registry.getGloves();
            case HEAD_ACCESSORY -> registry.getHeadAccessories();
            case FACE_ACCESSORY -> registry.getFaceAccessories();
            case EAR_ACCESSORY -> registry.getEarAccessories();
            case SKIN_FEATURE -> registry.getSkinFeatures();
            case CAPE -> registry.getCapes();
        };
        PlayerSkinPart part = parts == null ? null : parts.get(assetId);
        if (part == null) {
            return null;
        }
        Map<String, SkinOutfit.Variant> variants = null;
        if (part.getVariants() != null) {
            variants = new HashMap<>();
            for (Map.Entry<String, PlayerSkinPart.Variant> entry : part.getVariants().entrySet()) {
                PlayerSkinPart.Variant variant = entry.getValue();
                variants.put(entry.getKey(), new SkinOutfit.Variant(
                        variant.getModel(), variant.getGreyscaleTexture(), textures(variant.getTextures())));
            }
        }
        SkinOutfit.HeadCover cover = switch (part.getHeadAccessoryType()) {
            case HalfCovering -> SkinOutfit.HeadCover.HALF;
            case FullyCovering -> SkinOutfit.HeadCover.FULL;
            case Simple -> SkinOutfit.HeadCover.SIMPLE;
        };
        return new SkinOutfit.Part(part.getModel(), part.getGreyscaleTexture(), part.getGradientSet(),
                textures(part.getTextures()), variants,
                part.getHairType() == null ? null : part.getHairType().name(),
                part.doesRequireGenericHaircut(), cover);
    }

    private static Map<String, String> textures(Map<String, PlayerSkinPartTexture> textures) {
        if (textures == null) {
            return null;
        }
        Map<String, String> paths = new HashMap<>();
        textures.forEach((id, texture) -> paths.put(id, texture.getTexture()));
        return paths;
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
