package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.protocol.ModelAttachment;
import com.hypixel.hytale.protocol.PlayerSkin;
import org.junit.jupiter.api.Test;

import java.util.HashMap;
import java.util.List;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SkinOutfitTest {
    /** A slice of the vanilla cosmetics registry (Cosmetics/CharacterCreator/*.json). */
    private static final Map<String, SkinOutfit.Part> REGISTRY = new HashMap<>();

    static {
        put(SkinOutfit.Slot.BODY, "Default", greyscale("Characters/Player.blockymodel",
                "Characters/Player_Textures/Player_Greyscale.png", "Skin"));
        put(SkinOutfit.Slot.FACE, "Face_Neutral", greyscale("Characters/Body_Attachments/Faces/Player_Face_Detached.blockymodel",
                "Characters/Body_Attachments/Faces/Faces_Detached_Textures/Face.png", "Skin"));
        put(SkinOutfit.Slot.EYES, "Medium_Eyes", greyscale("Characters/Body_Attachments/Eyes/Eyes.blockymodel",
                "Characters/Body_Attachments/Eyes/Eyes_Textures/Greyscale.png", "Eyes_Gradient"));
        put(SkinOutfit.Slot.HAIRCUT, "Morning", new SkinOutfit.Part("Characters/Haircuts/Morning.blockymodel",
                "Characters/Haircuts/Morning_Greyscale.png", "Hair", null, null, "Short", true, SkinOutfit.HeadCover.SIMPLE));
        put(SkinOutfit.Slot.HAIRCUT, "GenericShort", greyscale("Characters/Haircuts/GenericShort.blockymodel",
                "Characters/Haircuts/GenericShort_Greyscale.png", "Hair"));
        put(SkinOutfit.Slot.PANTS, "Apprentice", new SkinOutfit.Part("Cosmetics/Pants/Pants_Large.blockymodel", null, null,
                Map.of("Brown", "Cosmetics/Pants/Pants_Large_Textures/Apprentice_Brown.png"), null, null, false,
                SkinOutfit.HeadCover.SIMPLE));
        put(SkinOutfit.Slot.OVERTOP, "PuffyJacket", new SkinOutfit.Part("Cosmetics/Overtops/Puffy_Jacket.blockymodel",
                "Cosmetics/Overtops/Overtops_Top_Textures/Puffy_Jacket_Greyscale.png", "Flashy_Synthetic", null,
                Map.of("Unzipped", new SkinOutfit.Variant("Cosmetics/Overtops/Puffy_Jacket_Unzipped.blockymodel",
                        "Cosmetics/Overtops/Overtops_Long_Sleeve_Textures/Sleeveless_Puffy_Jacket_Unzipped.png", null)),
                null, false, SkinOutfit.HeadCover.SIMPLE));
        put(SkinOutfit.Slot.HEAD_ACCESSORY, "Bandana", new SkinOutfit.Part("Cosmetics/Head/Bandana.blockymodel",
                "Cosmetics/Head/Bandana_Greyscale.png", "Colored_Cotton", null, null, null, false, SkinOutfit.HeadCover.HALF));
        put(SkinOutfit.Slot.HEAD_ACCESSORY, "Helmet", new SkinOutfit.Part("Cosmetics/Head/Helmet.blockymodel",
                "Cosmetics/Head/Helmet.png", null, null, null, null, false, SkinOutfit.HeadCover.FULL));
    }

    private static SkinOutfit.Part greyscale(String model, String texture, String gradientSet) {
        return new SkinOutfit.Part(model, texture, gradientSet, null, null, null, false, SkinOutfit.HeadCover.SIMPLE);
    }

    private static void put(SkinOutfit.Slot slot, String id, SkinOutfit.Part part) {
        REGISTRY.put(slot + "/" + id, part);
    }

    private static final SkinOutfit.Parts PARTS = (slot, id) -> REGISTRY.get(slot + "/" + id);

    private static PlayerSkin skin() {
        PlayerSkin skin = new PlayerSkin();
        skin.bodyCharacteristic = "Default.07";
        skin.face = "Face_Neutral";
        skin.eyes = "Medium_Eyes.Green";
        skin.haircut = "Morning.BrownDark";
        skin.pants = "Apprentice.Brown";
        skin.overtop = "PuffyJacket.Red.Unzipped";
        return skin;
    }

    @Test
    void theBodyRetexturesTheModelInTheSkinTone() {
        SkinOutfit.Outfit outfit = SkinOutfit.dress(skin(), PARTS);

        assertNotNull(outfit.body());
        assertEquals("Characters/Player_Textures/Player_Greyscale.png", outfit.body().texture);
        assertEquals("Skin", outfit.body().gradientSet);
        assertEquals("07", outfit.body().gradientId);
    }

    @Test
    void everyWornPartBecomesAnAttachment() {
        List<ModelAttachment> worn = SkinOutfit.dress(skin(), PARTS).attachments();

        ModelAttachment face = find(worn, "Player_Face_Detached");
        assertEquals("Skin", face.gradientSet);
        assertEquals("07", face.gradientId, "A face has no colour of its own; it takes the skin tone");

        ModelAttachment eyes = find(worn, "Eyes.blockymodel");
        assertEquals("Eyes_Gradient", eyes.gradientSet);
        assertEquals("Green", eyes.gradientId);

        ModelAttachment pants = find(worn, "Pants_Large");
        assertEquals("Cosmetics/Pants/Pants_Large_Textures/Apprentice_Brown.png", pants.texture,
                "A part with fixed textures uses the named one");
        assertNull(pants.gradientSet);

        ModelAttachment jacket = find(worn, "Puffy_Jacket_Unzipped");
        assertEquals("Cosmetics/Overtops/Overtops_Long_Sleeve_Textures/Sleeveless_Puffy_Jacket_Unzipped.png", jacket.texture);
        assertEquals("Flashy_Synthetic", jacket.gradientSet);
        assertEquals("Red", jacket.gradientId);

        ModelAttachment hair = find(worn, "Morning.blockymodel");
        assertEquals("BrownDark", hair.gradientId);
    }

    @Test
    void aHalfCoveringHatSwapsTheHaircutForTheGenericOne() {
        PlayerSkin skin = skin();
        skin.headAccessory = "Bandana.Blue";

        List<ModelAttachment> worn = SkinOutfit.dress(skin, PARTS).attachments();

        ModelAttachment hair = find(worn, "GenericShort");
        assertEquals("BrownDark", hair.gradientId);
        assertTrue(worn.stream().noneMatch(a -> a.model.contains("Morning")));
        assertNotNull(find(worn, "Bandana"));
    }

    @Test
    void aFullyCoveringHelmetHidesTheHair() {
        PlayerSkin skin = skin();
        skin.headAccessory = "Helmet";

        List<ModelAttachment> worn = SkinOutfit.dress(skin, PARTS).attachments();

        assertTrue(worn.stream().noneMatch(a -> a.model.contains("Haircuts")));
        assertNotNull(find(worn, "Helmet"));
    }

    @Test
    void unknownPartsAreSkippedNotFatal() {
        PlayerSkin skin = skin();
        skin.shoes = "NotAShoe.Black";
        skin.cape = "";

        List<ModelAttachment> worn = SkinOutfit.dress(skin, PARTS).attachments();

        assertEquals(5, worn.size(), "face, eyes, pants, jacket, hair");
    }

    @Test
    void noSkinMeansNoOutfit() {
        SkinOutfit.Outfit outfit = SkinOutfit.dress(null, PARTS);

        assertNull(outfit.body());
        assertTrue(outfit.attachments().isEmpty());
    }

    private static ModelAttachment find(List<ModelAttachment> worn, String modelFragment) {
        return worn.stream().filter(a -> a.model.contains(modelFragment)).findFirst()
                .orElseThrow(() -> new AssertionError("No attachment with model " + modelFragment + " in " + worn));
    }
}
