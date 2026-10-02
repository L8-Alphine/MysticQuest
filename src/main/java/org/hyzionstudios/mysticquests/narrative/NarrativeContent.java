package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.cutscene.CutsceneDefinition;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaAsset;
import org.hyzionstudios.mysticquests.narrative.media.Speaker;
import org.hyzionstudios.mysticquests.narrative.overlay.OverlayDefinition;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition;
import org.hyzionstudios.mysticquests.narrative.state.SchemaRegistry;

import java.util.List;
import java.util.Map;

/**
 * One compiled release of narrative content: schemas, puzzles, overlays, media, and the indexes built
 * from them.
 * Immutable, and swapped in whole on reload, so a running puzzle never sees half of two releases.
 *
 * @param triggerBindings puzzle inputs keyed by the trigger volume that fires them (§28: index
 *         triggers by stable id, so a volume event finds its inputs with one lookup)
 * @param packageVersions each package's manifest version, stamped onto the sessions it opens
 * @param report the warnings the compile produced; kept for the debugger
 */
public record NarrativeContent(
        SchemaRegistry schemas,
        Map<NamespacedId, PuzzleDefinition> puzzles,
        Map<NamespacedId, OverlayDefinition> overlays,
        Map<NamespacedId, Speaker> speakers,
        Map<NamespacedId, MediaAsset> media,
        Map<NamespacedId, CutsceneDefinition> cutscenes,
        Map<String, List<PuzzleBinding>> triggerBindings,
        Map<String, String> packageVersions,
        DiagnosticReport report) {

    public NarrativeContent {
        puzzles = Map.copyOf(puzzles);
        overlays = Map.copyOf(overlays);
        speakers = Map.copyOf(speakers);
        media = Map.copyOf(media);
        cutscenes = Map.copyOf(cutscenes);
        triggerBindings = Map.copyOf(triggerBindings);
        packageVersions = Map.copyOf(packageVersions);
    }

    /** A puzzle input fired by a trigger volume event. */
    public record PuzzleBinding(NamespacedId puzzle, String input, String event, boolean toggleable) {
    }

    public static NarrativeContent empty() {
        return new NarrativeContent(SchemaRegistry.empty(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), new DiagnosticReport());
    }

    public List<PuzzleBinding> bindings(String volumeKey) {
        return triggerBindings.getOrDefault(volumeKey, List.of());
    }

    public String version(String packageId) {
        return packageVersions.getOrDefault(packageId, "");
    }
}
