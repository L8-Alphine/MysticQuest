package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.protocol.ModelAttachment;
import com.hypixel.hytale.protocol.PlayerSkin;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Dresses a player model in a {@link PlayerSkin}, as model attachments, so a player-skinned NPC can
 * be shown in a prefab preview.
 *
 * <p>An entity with a {@code PlayerSkinComponent} wears the plain {@code Player} model
 * ({@code CosmeticsModule#createModel} builds it from the {@code Player} asset alone) and the skin
 * travels as a separate component: "playerSkin overrides the appearance of the model however the
 * model is still used for bounding box" ({@code PlayerSkinComponent}). A preview entity
 * ({@code ClipboardEntityChange}) carries only a {@code Model}, so the skin has to become part of the
 * model. Every skin slot is a cosmetic part id {@code Asset[.Texture[.Variant]]}; the server's
 * {@code CosmeticRegistry} holds each part's model, greyscale texture and gradient set (or its fixed
 * textures and variants), which are exactly the fields of a {@link ModelAttachment}. The body part
 * re-textures the base model itself; skin-coloured parts with no colour of their own (face, ears,
 * mouth) take the body's skin tone.
 *
 * <p>Pure: the registry is reached through {@link Parts}, so this is tested without the engine.
 */
final class SkinOutfit {
    static final String SKIN_GRADIENT_SET = "Skin";
    static final String GENERIC_HAIRCUT_PREFIX = "Generic";

    /** The cosmetic slots, each looked up in its own registry map. */
    enum Slot {
        BODY, UNDERWEAR, FACE, EYES, EARS, MOUTH, EYEBROWS, FACIAL_HAIR, HAIRCUT, PANTS, OVERPANTS,
        UNDERTOP, OVERTOP, SHOES, GLOVES, HEAD_ACCESSORY, FACE_ACCESSORY, EAR_ACCESSORY, SKIN_FEATURE, CAPE
    }

    enum HeadCover { SIMPLE, HALF, FULL }

    /** One cosmetic part, as the registry describes it. */
    record Part(String model, String greyscaleTexture, String gradientSet, Map<String, String> textures,
                Map<String, Variant> variants, String hairType, boolean requiresGenericHaircut,
                HeadCover headCover) {
    }

    record Variant(String model, String greyscaleTexture, Map<String, String> textures) {
    }

    /** The registry, slot by slot. */
    @FunctionalInterface
    interface Parts {
        Part find(Slot slot, String assetId);
    }

    /** A part id, {@code Asset[.Texture[.Variant]]}. */
    record PartId(String assetId, String textureId, String variantId) {
        static PartId parse(String id) {
            if (id == null || id.isBlank()) {
                return null;
            }
            String[] parts = id.trim().split("\\.", -1);
            return new PartId(parts[0], part(parts, 1), part(parts, 2));
        }

        private static String part(String[] parts, int index) {
            return parts.length > index && !parts[index].isEmpty() ? parts[index] : null;
        }
    }

    /** The body's look and everything worn over it. {@code body} is null when the skin names none. */
    record Outfit(ModelAttachment body, List<ModelAttachment> attachments) {
    }

    private SkinOutfit() {
    }

    static Outfit dress(PlayerSkin skin, Parts parts) {
        if (skin == null || parts == null) {
            return new Outfit(null, List.of());
        }
        PartId bodyId = PartId.parse(skin.bodyCharacteristic);
        String skinTone = bodyId == null ? null : bodyId.textureId();
        ModelAttachment body = attachment(parts, Slot.BODY, bodyId, skinTone);

        List<ModelAttachment> worn = new ArrayList<>();
        add(worn, parts, Slot.FACE, skin.face, skinTone);
        add(worn, parts, Slot.EYES, skin.eyes, skinTone);
        add(worn, parts, Slot.EARS, skin.ears, skinTone);
        add(worn, parts, Slot.MOUTH, skin.mouth, skinTone);
        add(worn, parts, Slot.SKIN_FEATURE, skin.skinFeature, skinTone);
        add(worn, parts, Slot.EYEBROWS, skin.eyebrows, skinTone);
        add(worn, parts, Slot.FACIAL_HAIR, skin.facialHair, skinTone);
        add(worn, parts, Slot.UNDERWEAR, skin.underwear, skinTone);
        add(worn, parts, Slot.PANTS, skin.pants, skinTone);
        add(worn, parts, Slot.OVERPANTS, skin.overpants, skinTone);
        add(worn, parts, Slot.UNDERTOP, skin.undertop, skinTone);
        add(worn, parts, Slot.OVERTOP, skin.overtop, skinTone);
        add(worn, parts, Slot.SHOES, skin.shoes, skinTone);
        add(worn, parts, Slot.GLOVES, skin.gloves, skinTone);
        add(worn, parts, Slot.FACE_ACCESSORY, skin.faceAccessory, skinTone);
        add(worn, parts, Slot.EAR_ACCESSORY, skin.earAccessory, skinTone);
        add(worn, parts, Slot.CAPE, skin.cape, skinTone);

        PartId headId = PartId.parse(skin.headAccessory);
        Part head = headId == null ? null : parts.find(Slot.HEAD_ACCESSORY, headId.assetId());
        HeadCover cover = head == null || head.headCover() == null ? HeadCover.SIMPLE : head.headCover();
        PartId hairId = haircut(PartId.parse(skin.haircut), cover, parts);
        if (hairId != null) {
            addIfPresent(worn, attachment(parts, Slot.HAIRCUT, hairId, skinTone));
        }
        if (headId != null) {
            addIfPresent(worn, attachment(parts, Slot.HEAD_ACCESSORY, headId, skinTone));
        }
        return new Outfit(body, List.copyOf(worn));
    }

    /**
     * The haircut actually drawn under a head accessory, mirroring
     * {@code CosmeticsModule#isValidHaircutAttachment}: a half-covering accessory swaps a haircut that
     * needs it for the generic one of the same length, and a fully covering one hides the hair.
     */
    private static PartId haircut(PartId haircut, HeadCover cover, Parts parts) {
        if (haircut == null || cover == HeadCover.FULL) {
            return null;
        }
        if (cover == HeadCover.HALF) {
            Part part = parts.find(Slot.HAIRCUT, haircut.assetId());
            if (part != null && part.requiresGenericHaircut() && part.hairType() != null) {
                return new PartId(GENERIC_HAIRCUT_PREFIX + part.hairType(), haircut.textureId(), null);
            }
        }
        return haircut;
    }

    private static void add(List<ModelAttachment> worn, Parts parts, Slot slot, String id, String skinTone) {
        addIfPresent(worn, attachment(parts, slot, PartId.parse(id), skinTone));
    }

    private static void addIfPresent(List<ModelAttachment> worn, ModelAttachment attachment) {
        if (attachment != null) {
            worn.add(attachment);
        }
    }

    /** One part as an attachment, or null when the registry does not know it or it has no model. */
    static ModelAttachment attachment(Parts parts, Slot slot, PartId id, String skinTone) {
        if (id == null) {
            return null;
        }
        Part part = parts.find(slot, id.assetId());
        if (part == null) {
            return null;
        }
        Variant variant = part.variants() == null || id.variantId() == null ? null : part.variants().get(id.variantId());
        if (variant == null && part.variants() != null && !part.variants().isEmpty() && part.model() == null) {
            variant = part.variants().values().iterator().next();
        }
        String model = variant != null && variant.model() != null ? variant.model() : part.model();
        if (model == null) {
            return null;
        }
        Map<String, String> textures = variant != null && variant.textures() != null ? variant.textures() : part.textures();
        if (id.textureId() != null && textures != null && textures.containsKey(id.textureId())) {
            return new ModelAttachment(model, textures.get(id.textureId()), null, null);
        }
        String greyscale = variant != null && variant.greyscaleTexture() != null
                ? variant.greyscaleTexture()
                : part.greyscaleTexture();
        if (greyscale == null && textures != null && !textures.isEmpty()) {
            return new ModelAttachment(model, textures.values().iterator().next(), null, null);
        }
        String gradientId = id.textureId() != null
                ? id.textureId()
                : SKIN_GRADIENT_SET.equals(part.gradientSet()) ? skinTone : null;
        return new ModelAttachment(model, greyscale, part.gradientSet(), gradientId);
    }
}
