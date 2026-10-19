package org.hyzionstudios.mysticquests.integration.hud;

import org.hyzionstudios.mysticquests.ui.HiddenHudKeys;

import com.hypixel.hytale.protocol.packets.interface_.CustomHud;
import com.hypixel.hytale.protocol.packets.interface_.CustomUICommand;
import org.junit.jupiter.api.Test;

import java.util.List;
import java.util.Set;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class HudOverrideFilterTest {
    private static final HiddenHudKeys HIDDEN = HiddenHudKeys.of(List.of("EndgameQoL.QuestTracker", "ziggfreedcommon:*"));

    @Test
    void dropsShowsAndPatchesOfHiddenLayers() {
        CustomUICommand[] commands = {new CustomUICommand()};
        assertTrue(HudOverrideFilter.drops(HIDDEN, new CustomHud("EndgameQoL.QuestTracker", 0, true, commands)), "show");
        assertTrue(HudOverrideFilter.drops(HIDDEN, new CustomHud("EndgameQoL.QuestTracker", 0, false, commands)), "patch");
        assertTrue(HudOverrideFilter.drops(HIDDEN, new CustomHud("ziggfreedcommon:quest_tracker", 0, false, null)), "prefix");
    }

    @Test
    void letsRemovalsAndOtherLayersThrough() {
        assertFalse(HudOverrideFilter.drops(HIDDEN, new CustomHud("EndgameQoL.QuestTracker", 0, true, null)), "removal");
        assertFalse(HudOverrideFilter.drops(HIDDEN, new CustomHud("EndgameQoL.QuestTracker", 0, true, new CustomUICommand[0])));
        assertFalse(HudOverrideFilter.drops(HIDDEN, new CustomHud("EndgameQoL.Combo", 0, true, new CustomUICommand[] {new CustomUICommand()})));
    }

    @Test
    void neverHidesMysticQuestsOwnLayers() {
        HiddenHudKeys greedy = HiddenHudKeys.of(List.of("mysticquests:*", "*", "mysticquests:quest_tracker"));
        assertFalse(greedy.hides("mysticquests:quest_tracker"));
        assertFalse(greedy.hides("EndgameQoL.Combo"), "a lone * is ignored rather than hiding every mod's HUD");
    }

    @Test
    void keysCombineAndDescribe() {
        HiddenHudKeys combined = HiddenHudKeys.of(List.of(" a ", "", "b*")).plus(HiddenHudKeys.of(List.of("c")));
        assertEquals(Set.of("a", "b*", "c"), combined.describe());
        assertTrue(combined.hides("bee"));
        assertFalse(HiddenHudKeys.NONE.hides("a"));
        assertTrue(HiddenHudKeys.of(List.of()).isEmpty());
    }
}
