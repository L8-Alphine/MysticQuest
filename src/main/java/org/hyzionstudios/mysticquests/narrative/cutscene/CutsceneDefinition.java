package org.hyzionstudios.mysticquests.narrative.cutscene;

import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.AudienceMode;

import java.util.List;
import java.util.Objects;

/**
 * A lightweight cutscene timeline (§17): actions placed at times, plus the finalization that must
 * happen however the scene ends.
 *
 * <pre>
 *   { "id": "hyzion:druid_temple.awakening", "story": "hyzion:druid_temple", "skippable": true,
 *     "steps": [
 *       { "at": 0,   "type": "setCamera", "mode": "third", "locked": true },
 *       { "at": 0.5, "type": "mysticquests:media.play", "media": "hyzion:narrator.intro", "cosmetic": true },
 *       { "at": 6,   "type": "mysticquests:tag.add", "tag": "hyzion:druid_temple.awakened" } ],
 *     "onEnd": [ { "type": "setCamera", "mode": "first" } ] }
 * </pre>
 *
 * @param steps in time order; equal times keep their authored order
 * @param onEnd runs once whenever the scene ends: played out, skipped, replaced, or recovered after
 *         a disconnect or restart. Restoring the camera and controls belongs here
 */
public record CutsceneDefinition(
        NamespacedId id,
        String packageId,
        String story,
        AudienceMode audience,
        boolean skippable,
        List<Step> steps,
        List<ActionDefinition> onEnd) {

    /**
     * One timed action.
     *
     * @param key stable within the cutscene: the authored {@code stepId}, else the position
     * @param cosmetic presentation only; a skip drops it instead of running it
     */
    public record Step(String key, long atMillis, boolean cosmetic, ActionDefinition action) {
    }

    public CutsceneDefinition {
        Objects.requireNonNull(id, "id");
        steps = List.copyOf(steps);
        onEnd = List.copyOf(onEnd);
    }

    public long lengthMillis() {
        return steps.isEmpty() ? 0 : steps.getLast().atMillis();
    }
}
