package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

public final class QuestContentLoader {
    private static final Set<String> OBJECTIVE_TYPES = Set.of(
            "kill", "gather", "craft", "triggerEnter", "triggerExit", "interactEntity",
            "interactObject", "reachLocation", "dialogue", "timer", "custom");
    private static final Set<String> CONDITION_TYPES = Set.of(
            "tag", "globalTag", "entityTag", "blockTag",
            "volumeTag",
            "questCompleted", "questActive",
            "variable", "globalVariable", "entityVariable", "blockVariable",
            "volumeVariable",
            "level", "permission", "economy", "playerHidden", "inConversation",
            "and", "or", "not", "custom");
    private static final Set<String> EVENT_TYPES = Set.of(
            "giveItem", "removeItem", "runCommand", "sendMessage", "startQuest", "completeQuest",
            "addTag", "removeTag", "setVariable", "removeVariable", "incrementVariable",
            "globalTag", "globalVariable", "entityTag", "entityVariable", "blockTag", "blockVariable",
            "volumeTag", "volumeVariable",
            "modifyMoney", "packetEffect", "triggerHyExtrasEffect",
            "notification", "folder", "if", "runForAll", "runIndependent", "cancelConversation",
            "hidePlayer", "showPlayer", "custom");

    private final ObjectMapper mapper;

    public QuestContentLoader(ObjectMapper mapper) {
        this.mapper = mapper;
    }

    public LoadedContent load(Path packagesPath) throws IOException {
        if (Files.notExists(packagesPath)) {
            Files.createDirectories(packagesPath);
            return LoadedContent.empty();
        }

        Set<String> packageIds = new LinkedHashSet<>();
        Map<String, QuestDefinition> quests = new LinkedHashMap<>();
        Map<String, ConditionDefinition> conditions = new LinkedHashMap<>();
        Map<String, EventDefinition> events = new LinkedHashMap<>();
        Map<String, ConversationDefinition> conversations = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        try (var stream = Files.list(packagesPath)) {
            for (Path packagePath : stream.filter(Files::isDirectory).sorted().toList()) {
                String packageId = packagePath.getFileName().toString();
                packageIds.add(packageId);
                readDefinitions(packagePath.resolve("conditions.json"), "conditions", new TypeReference<List<ConditionDefinition>>() {}, packageId, conditions, errors);
                readDefinitions(packagePath.resolve("events.json"), "events", new TypeReference<List<EventDefinition>>() {}, packageId, events, errors);
                readConversations(packagePath.resolve("conversations.json"), packageId, conversations, errors);
                readQuests(packagePath.resolve("quests.json"), packageId, quests, errors);
            }
        }

        LoadedContent loaded = new LoadedContent(packageIds, quests, conditions, events, conversations);
        validate(loaded, errors);
        if (!errors.isEmpty()) {
            throw new IOException("MysticQuests package validation failed:\n - " + String.join("\n - ", errors));
        }
        return loaded;
    }

    private <T extends TypedConfig> void readDefinitions(
            Path file,
            String wrapper,
            TypeReference<List<T>> type,
            String packageId,
            Map<String, T> target,
            List<String> errors) throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        List<T> definitions = readList(file, wrapper, type);
        for (T definition : definitions) {
            String id = definition.text("id", null);
            if (id == null || id.isBlank()) {
                errors.add(file + " contains a definition without id");
                continue;
            }
            putUnique(packageId + ":" + id, definition, target, errors, file);
        }
    }

    private void readQuests(Path file, String packageId, Map<String, QuestDefinition> quests, List<String> errors) throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        for (QuestDefinition quest : readList(file, "quests", new TypeReference<List<QuestDefinition>>() {})) {
            if (quest.id() == null || quest.id().isBlank()) {
                errors.add(file + " contains a quest without id");
                continue;
            }
            quest.setPackageId(packageId);
            putUnique(packageId + ":" + quest.id(), quest, quests, errors, file);
        }
    }

    private void readConversations(Path file, String packageId, Map<String, ConversationDefinition> conversations, List<String> errors) throws IOException {
        if (Files.notExists(file)) {
            return;
        }
        for (ConversationDefinition conversation : readList(file, "conversations", new TypeReference<List<ConversationDefinition>>() {})) {
            if (conversation.id() == null || conversation.id().isBlank()) {
                errors.add(file + " contains a conversation without id");
                continue;
            }
            conversation.setPackageId(packageId);
            putUnique(packageId + ":" + conversation.id(), conversation, conversations, errors, file);
        }
    }

    private <T> List<T> readList(Path file, String wrapper, TypeReference<List<T>> type) throws IOException {
        JsonNode root = mapper.readTree(file.toFile());
        JsonNode listNode = root.isArray() ? root : root.get(wrapper);
        if (listNode == null || !listNode.isArray()) {
            throw new IOException(file + " must be an array or contain an array field named '" + wrapper + "'");
        }
        return mapper.convertValue(listNode, type);
    }

    private <T> void putUnique(String id, T value, Map<String, T> target, List<String> errors, Path file) {
        if (target.putIfAbsent(id, value) != null) {
            errors.add(file + " duplicates id " + id);
        }
    }

    private void validate(LoadedContent content, List<String> errors) {
        for (Map.Entry<String, QuestDefinition> entry : content.quests().entrySet()) {
            QuestDefinition quest = entry.getValue();
            validateTypedList(entry.getKey(), "start condition", quest.startConditions(), CONDITION_TYPES, errors);
            validateTypedList(entry.getKey(), "start event", quest.startEvents(), EVENT_TYPES, errors);
            validateTypedList(entry.getKey(), "complete event", quest.completeEvents(), EVENT_TYPES, errors);
            validateTypedList(entry.getKey(), "reward", quest.rewards(), EVENT_TYPES, errors);
            validateObjectives(entry.getKey(), quest.objectives(), errors);
            if (quest.objectives().isEmpty()) {
                errors.add(entry.getKey() + " must declare at least one objective");
            }
        }
        for (Map.Entry<String, ConversationDefinition> entry : content.conversations().entrySet()) {
            ConversationDefinition conversation = entry.getValue();
            if (conversation.nodes().isEmpty()) {
                errors.add(entry.getKey() + " conversation has no nodes");
                continue;
            }
            validateConversation(entry.getKey(), conversation, errors);
        }
        validateTypedMap("condition", content.conditions(), CONDITION_TYPES, errors);
        validateTypedMap("event", content.events(), EVENT_TYPES, errors);
    }

    private void validateConversation(String conversationId, ConversationDefinition conversation, List<String> errors) {
        Set<String> nodeIds = new LinkedHashSet<>();
        for (var node : conversation.nodes()) {
            if (node.id() == null || node.id().isBlank()) {
                errors.add(conversationId + " contains a conversation node without id");
                continue;
            }
            if (!nodeIds.add(node.id())) {
                errors.add(conversationId + " duplicates conversation node id " + node.id());
            }
            validateTypedList(conversationId + "/" + node.id(), "node condition", node.conditions(), CONDITION_TYPES, errors);
            validateTypedList(conversationId + "/" + node.id(), "node event", node.events(), EVENT_TYPES, errors);
            for (var choice : node.choices()) {
                validateTypedList(conversationId + "/" + node.id(), "choice condition", choice.conditions(), CONDITION_TYPES, errors);
                validateTypedList(conversationId + "/" + node.id(), "choice event", choice.events(), EVENT_TYPES, errors);
            }
        }
        String start = conversation.start();
        if (start != null && !nodeIds.contains(start)) {
            errors.add(conversationId + " start node does not exist: " + start);
        }
        for (var node : conversation.nodes()) {
            for (var choice : node.choices()) {
                String next = choice.next();
                if (next != null && !next.isBlank() && !nodeIds.contains(next) && !next.equalsIgnoreCase("end")) {
                    errors.add(conversationId + "/" + node.id() + " choice points to missing node " + next);
                }
            }
        }
    }

    private void validateObjectives(String questId, List<ObjectiveDefinition> objectives, List<String> errors) {
        Set<String> seen = new LinkedHashSet<>();
        for (ObjectiveDefinition objective : objectives) {
            if (objective.id() == null || objective.id().isBlank()) {
                errors.add(questId + " contains an objective without id");
            } else if (!seen.add(objective.id())) {
                errors.add(questId + " duplicates objective id " + objective.id());
            }
            validateType(questId + "/" + objective.id(), "objective", objective, OBJECTIVE_TYPES, errors);
        }
    }

    private <T extends TypedConfig> void validateTypedList(String owner, String label, List<T> values, Set<String> allowedTypes, List<String> errors) {
        for (T value : values) {
            validateType(owner, label, value, allowedTypes, errors);
        }
    }

    private <T extends TypedConfig> void validateTypedMap(String label, Map<String, T> values, Set<String> allowedTypes, List<String> errors) {
        for (Map.Entry<String, T> entry : values.entrySet()) {
            validateType(entry.getKey(), label, entry.getValue(), allowedTypes, errors);
        }
    }

    private void validateType(String owner, String label, TypedConfig value, Set<String> allowedTypes, List<String> errors) {
        if (value.type() == null || value.type().isBlank()) {
            errors.add(owner + " contains " + label + " without type");
        } else if (!allowedTypes.contains(value.type())) {
            errors.add(owner + " uses unknown " + label + " type " + value.type());
        }
    }
}
