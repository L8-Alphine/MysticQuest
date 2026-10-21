package org.hyzionstudios.mysticquests.service;

import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNull;

final class SpeakerPortraitsTest {
    /** Stands in for the model asset map: the Icon each model declares. */
    private static final Function<String, String> MODEL_ICONS = Map.of(
            "Kweebec_Sapling", "Icons/ModelsGenerated/Kweebec_Sapling.png",
            "Elder_Model", "Icons/Custom/Elder.png")::get;

    @Test
    void triesAuthoredThenModelIconThenModelThenRole() {
        List<String> candidates = SpeakerPortraits.candidates(
                "Common\\Icons/Custom/Rowan.png", "Elder_Model", "Village_Elder", MODEL_ICONS);

        assertEquals(List.of(
                "Icons/Custom/Rowan.png",
                "Icons/Custom/Elder.png",
                "Icons/ModelsGenerated/Elder_Model.png",
                "Icons/ModelsGenerated/Village_Elder.png"), candidates);
    }

    @Test
    void anAuthoredModelIdUsesThatModelsPortrait() {
        List<String> candidates = SpeakerPortraits.candidates("Kweebec_Sapling", null, null, MODEL_ICONS);

        assertEquals(List.of("Icons/ModelsGenerated/Kweebec_Sapling.png"), candidates,
                "The model's Icon and the generated path coincide, so it is tried once");
    }

    @Test
    void nothingKnownMeansNoCandidates() {
        assertEquals(List.of(), SpeakerPortraits.candidates(" ", null, "", MODEL_ICONS));
    }

    @Test
    void choosesTheFirstPortraitThatExists() {
        List<String> candidates = SpeakerPortraits.candidates(null, "Player", "Kweebec_Sapling", MODEL_ICONS);
        Set<String> shipped = Set.of("Icons/ModelsGenerated/Kweebec_Sapling.png");

        assertEquals("Icons/ModelsGenerated/Kweebec_Sapling.png",
                SpeakerPortraits.choose(candidates, shipped::contains),
                "A Player-skinned NPC has no model portrait, so its role's is used");
        assertNull(SpeakerPortraits.choose(candidates, path -> false),
                "With no portrait the card falls back to initials");
    }

    @Test
    void normalisesAuthoredPaths() {
        assertEquals("Icons/A.png", SpeakerPortraits.normalisePath("  /Common/Icons/A.png "));
        assertEquals("Icons/A.png", SpeakerPortraits.normalisePath("Icons\\A.png"));
    }
}
