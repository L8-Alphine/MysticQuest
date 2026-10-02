package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;

import java.util.UUID;

/**
 * Lets v1 quest content run 2.0 narrative script: the {@code narrative} event runs a list of
 * narrative actions, and the {@code narrative} condition tests a narrative condition tree.
 *
 * <pre>
 *   { "type": "narrative", "story": "hyzion:druid_temple",
 *     "actions": [ { "type": "mysticquests:cutscene.play", "cutscene": "hyzion:druid_temple.opening" } ] }
 *   { "type": "narrative", "condition": { "type": "tag", "tag": "hyzion:druid_temple.keys_complete", "scope": "quest_session" },
 *     "story": "hyzion:druid_temple" }
 * </pre>
 *
 * <p>Bound by the narrative integration; with none bound the event does nothing and the condition
 * denies, so a quest gate never opens by accident.
 */
public interface NarrativeScripts {
    String TYPE = "narrative";

    void run(UUID playerId, String packageId, EventDefinition event, QuestTargetContext target);

    boolean test(UUID playerId, String packageId, ConditionDefinition condition);
}
