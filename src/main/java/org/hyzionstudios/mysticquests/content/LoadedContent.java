package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Set;

public record LoadedContent(
        Set<String> packages,
        Map<String, QuestDefinition> quests,
        Map<String, ConditionDefinition> conditions,
        Map<String, EventDefinition> events,
        Map<String, ConversationDefinition> conversations) {
    public LoadedContent {
        packages = Collections.unmodifiableSet(new LinkedHashSet<>(packages));
        quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        conditions = Collections.unmodifiableMap(new LinkedHashMap<>(conditions));
        events = Collections.unmodifiableMap(new LinkedHashMap<>(events));
        conversations = Collections.unmodifiableMap(new LinkedHashMap<>(conversations));
    }

    public static LoadedContent empty() {
        return new LoadedContent(Set.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public String resolveId(String packageId, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return rawId;
        }
        return rawId.contains(":") ? rawId : packageId + ":" + rawId;
    }
}
