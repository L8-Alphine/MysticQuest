package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public record LoadedContent(
        Set<String> packages,
        Map<String, QuestDefinition> quests,
        Map<String, ConditionDefinition> conditions,
        Map<String, EventDefinition> events,
        Map<String, ObjectiveDefinition> objectives,
        Map<String, ConversationDefinition> conversations,
        Map<String, JsonNode> items,
        Map<String, JsonNode> cancelers,
        Map<String, JsonNode> schedules,
        Map<String, JsonNode> functions,
        Map<String, JsonNode> notifications,
        Map<String, JsonNode> playerHiders,
        Map<String, JsonNode> constants,
        Map<String, PackageMetadata> packageMetadata,
        Map<String, JsonNode> narrativeSections) {
    /**
     * Package sections compiled by the narrative runtime rather than this loader. Each value holds
     * whichever of these the package defines, keyed by package id.
     */
    public static final List<String> NARRATIVE_SECTIONS = List.of(
            "variableSchemas", "tagSchemas", "puzzles", "overlays", "speakers", "media", "cutscenes");

    public LoadedContent {
        packages = Collections.unmodifiableSet(new LinkedHashSet<>(packages));
        quests = Collections.unmodifiableMap(new LinkedHashMap<>(quests));
        conditions = Collections.unmodifiableMap(new LinkedHashMap<>(conditions));
        events = Collections.unmodifiableMap(new LinkedHashMap<>(events));
        objectives = Collections.unmodifiableMap(new LinkedHashMap<>(objectives));
        conversations = Collections.unmodifiableMap(new LinkedHashMap<>(conversations));
        items = Collections.unmodifiableMap(new LinkedHashMap<>(items));
        cancelers = Collections.unmodifiableMap(new LinkedHashMap<>(cancelers));
        schedules = Collections.unmodifiableMap(new LinkedHashMap<>(schedules));
        functions = Collections.unmodifiableMap(new LinkedHashMap<>(functions));
        notifications = Collections.unmodifiableMap(new LinkedHashMap<>(notifications));
        playerHiders = Collections.unmodifiableMap(new LinkedHashMap<>(playerHiders));
        constants = Collections.unmodifiableMap(new LinkedHashMap<>(constants));
        packageMetadata = Collections.unmodifiableMap(new LinkedHashMap<>(packageMetadata));
        narrativeSections = Collections.unmodifiableMap(new LinkedHashMap<>(narrativeSections));
    }

    public static LoadedContent empty() {
        return new LoadedContent(
                Set.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(),
                Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of(), Map.of());
    }

    public String resolveId(String packageId, String rawId) {
        if (rawId == null || rawId.isBlank()) {
            return rawId;
        }
        if (rawId.contains(":")) {
            return rawId;
        }
        int crossPackage = rawId.indexOf('>');
        if (crossPackage > 0) {
            String packagePath = rawId.substring(0, crossPackage).replace('-', '/');
            return packagePath + ":" + rawId.substring(crossPackage + 1);
        }
        return packageId + ":" + rawId;
    }
}
