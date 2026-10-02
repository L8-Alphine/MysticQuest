package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §21: the live debugger shows a player's whole story state and changes nothing while looking. */
final class NarrativeDebugTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID SEAL = UUID.fromString("00000000-0000-4000-8000-0000000005ea");

    private final NarrativeTestKit kit = new NarrativeTestKit();

    @AfterEach
    void tearDown() {
        kit.close();
    }

    @Test
    void aSnapshotCoversEveryLayerOfTheStory() {
        kit.load("""
                {
                  "tagSchemas": [ { "id": "hyzion:temple.opened", "scope": "quest_session" } ],
                  "overlays": [ { "id": "hyzion:temple.seal", "entity": "uuid:%s" } ],
                  "cutscenes": [ { "id": "hyzion:temple.scene", "story": "hyzion:temple",
                    "steps": [ { "at": 30, "type": "mysticquests:tag.add", "tag": "hyzion:temple.opened" } ] } ],
                  "puzzles": [ { "id": "hyzion:temple.lever", "story": "hyzion:temple", "inputs": ["a", "b"], "rule": "all",
                    "outputs": [ { "type": "mysticquests:overlay.hide", "overlay": "hyzion:temple.seal" },
                                 { "type": "mysticquests:cutscene.play", "cutscene": "hyzion:temple.scene" } ] } ]
                }
                """.formatted(SEAL));
        kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "a", true);
        Map<String, List<String>> partial = kit.runtime().debug().snapshot(ALICE);
        assertTrue(partial.get("Puzzles").getFirst().contains("activated [a]"), partial.toString());

        kit.runtime().puzzles().input(ALICE, null, id("hyzion:temple.lever"), "b", true);
        Map<String, List<String>> snapshot = kit.runtime().debug().snapshot(ALICE);
        assertEquals(1, snapshot.get("Sessions").size(), snapshot.toString());
        assertTrue(snapshot.get("Puzzles").getFirst().endsWith("solved"), snapshot.toString());
        assertTrue(snapshot.get("World overlays").getFirst().startsWith("hyzion:temple.seal: absent (story_session"), snapshot.toString());
        assertTrue(snapshot.get("Cutscene").getFirst().startsWith("hyzion:temple.scene"), snapshot.toString());
        assertTrue(snapshot.get("Media").stream().anyMatch(line -> line.equals("music: world")), snapshot.toString());
    }

    @Test
    void lookingAtAPlayerSavesNothingForThem() throws IOException {
        UUID stranger = UUID.randomUUID();
        Map<String, List<String>> snapshot = kit.runtime().debug().snapshot(stranger);
        assertTrue(snapshot.get("Sessions").isEmpty());
        assertTrue(snapshot.get("Variables").isEmpty());
        kit.runtime().flush();
        assertTrue(kit.store.read("state", ScopeOwner.player(stranger).key()).isEmpty(), "nothing was saved for them");
    }
}
