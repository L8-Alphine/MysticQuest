package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveMarker;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.StageDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.Comparator;
import java.util.function.BiConsumer;

public final class QuestContentLoader {
    private static final Set<String> OBJECTIVE_TYPES = Set.of(
            "kill", "gather", "craft", "triggerEnter", "triggerExit", "interactEntity",
            "interactObject", "interactNpc", "reachLocation", "dialogue", "timer", "custom", "signal");
    /**
     * Legacy aliases are rewritten onto the canonical {@code tag}/{@code variable} types by
     * {@link ContentSchema} before validation, so they are not listed here. They stay accepted at
     * runtime for definitions built in code.
     */
    private static final Set<String> CONDITION_TYPES = Set.of(
            "tag", "variable",
            "questCompleted", "questActive",
            "permission", "economy", "inConversation", "inParty", "partySize",
            "playerHidden", "entityHidden", "targetingPrevented", "nearEntity",
            "and", "or", "not", "ref",
            // 2.0 narrative condition trees; their contents are checked by the narrative compiler.
            "narrative");
    private static final Set<String> EVENT_TYPES = Set.of(
            "tag", "variable",
            "giveItem", "removeItem", "runCommand", "sendMessage", "startQuest", "completeQuest",
            "modifyMoney", "triggerHyExtrasEffect",
            "hidePlayer", "showPlayer", "hideEntity", "showEntity",
            "preventTargeting", "allowTargeting", "setCamera", "sendTitle", "actionBar",
            "notification", "folder", "party", "if", "cancelConversation", "cancelQuest", "ref",
            // 2.0 narrative action lists; their contents are checked by the narrative compiler.
            "narrative");

    /** Composite conditions whose children must themselves validate. */
    private static final Set<String> COMPOSITE_CONDITION_TYPES = Set.of("and", "or", "not");

    /**
     * Types that were previously accepted at load and then silently ignored at execution. Rejecting
     * them with an explanation beats letting a quest gate read as "passed" or a reward vanish.
     */
    private static final Map<String, String> UNSUPPORTED_CONDITION_TYPES = Map.of(
            "level", "no level provider is integrated; gate on a variable or tag instead",
            "custom", "register a condition type through MysticQuestsRegistry and use its id instead");
    private static final Map<String, String> UNSUPPORTED_EVENT_TYPES = Map.of(
            "runForAll", "multi-player fan-out is not implemented; use 'party' or target 'players'",
            "runIndependent", "detached execution is not implemented",
            "packetEffect", "never did anything; use sendTitle, actionBar, or setCamera",
            "custom", "register an event type through MysticQuestsRegistry and use its id instead");

    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;
    private final ScriptInstructionParser instructions;

    public QuestContentLoader(ObjectMapper mapper) {
        this.mapper = mapper;
        this.yamlMapper = Json.createYamlMapper();
        this.instructions = new ScriptInstructionParser(mapper);
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
        Map<String, ObjectiveDefinition> objectives = new LinkedHashMap<>();
        Map<String, ConversationDefinition> conversations = new LinkedHashMap<>();
        Map<String, JsonNode> items = new LinkedHashMap<>();
        Map<String, JsonNode> cancelers = new LinkedHashMap<>();
        Map<String, JsonNode> schedules = new LinkedHashMap<>();
        Map<String, JsonNode> functions = new LinkedHashMap<>();
        Map<String, JsonNode> notifications = new LinkedHashMap<>();
        Map<String, JsonNode> playerHiders = new LinkedHashMap<>();
        Map<String, JsonNode> constants = new LinkedHashMap<>();
        Map<String, PackageMetadata> metadata = new LinkedHashMap<>();
        Map<String, JsonNode> narrativeSections = new LinkedHashMap<>();
        List<String> errors = new ArrayList<>();

        List<PackageRoot> packageRoots = discoverPackages(packagesPath);
        Path templatesPath = packagesPath.resolveSibling("templates");
        List<PackageRoot> templateRoots = discoverPackages(templatesPath);
        Map<String, PackageRoot> templatesById = new LinkedHashMap<>();
        for (PackageRoot template : templateRoots) {
            templatesById.put(template.id(), template);
        }

        for (PackageRoot packageRoot : packageRoots) {
            PackageManifest manifest = manifest(packageRoot.directory());
            metadata.put(packageRoot.id(), new PackageMetadata(
                    packageRoot.id(), packageRoot.directory(), manifest.enabled(), manifest.version(), manifest.templates()));
            if (!manifest.enabled()) {
                continue;
            }
            packageIds.add(packageRoot.id());
            ObjectNode merged = mapper.createObjectNode();
            Set<String> templateStack = new LinkedHashSet<>();
            for (String templateId : manifest.templates()) {
                mergeTemplate(merged, templateId, templatesById, templateRoots, templateStack, errors);
            }
            mergeDocument(merged, readPackageDocuments(packageRoot, packageRoots), true);

            readTypedSection(merged.get("conditions"), packageRoot.id(), conditions, errors, packageRoot.directory(),
                    (id, node) -> parseCondition(id, node));
            readTypedSection(merged.get("events"), packageRoot.id(), events, errors, packageRoot.directory(),
                    (id, node) -> parseEvent(id, node));
            readTypedSection(merged.get("actions"), packageRoot.id(), events, errors, packageRoot.directory(),
                    (id, node) -> parseEvent(id, node));
            readTypedSection(merged.get("objectives"), packageRoot.id(), objectives, errors, packageRoot.directory(),
                    this::parseObjective);
            readConversationSection(merged.get("conversations"), packageRoot.id(), conversations, errors, packageRoot.directory());
            readQuestSection(merged.get("quests"), packageRoot.id(), quests, errors, packageRoot.directory());
            readRawSection(merged.get("items"), packageRoot.id(), items, errors, packageRoot.directory());
            readRawSection(first(merged, "cancelers", "cancel"), packageRoot.id(), cancelers, errors, packageRoot.directory());
            readRawSection(merged.get("schedules"), packageRoot.id(), schedules, errors, packageRoot.directory());
            readRawSection(merged.get("functions"), packageRoot.id(), functions, errors, packageRoot.directory());
            readRawSection(merged.get("notifications"), packageRoot.id(), notifications, errors, packageRoot.directory());
            readRawSection(first(merged, "playerHiders", "player_hiders"), packageRoot.id(), playerHiders, errors, packageRoot.directory());
            readRawSection(merged.get("constants"), packageRoot.id(), constants, errors, packageRoot.directory());
            // Narrative sections are validated by the narrative compiler, which needs every package's
            // schemas before it can check any package's puzzles; this loader only carries them across.
            ObjectNode narrative = mapper.createObjectNode();
            for (String section : LoadedContent.NARRATIVE_SECTIONS) {
                JsonNode value = merged.get(section);
                if (value != null && !value.isNull()) {
                    narrative.set(section, value.deepCopy());
                }
            }
            if (!narrative.isEmpty()) {
                narrativeSections.put(packageRoot.id(), narrative);
            }
        }

        resolveObjectiveReferences(quests, objectives, errors);
        LoadedContent loaded = new LoadedContent(
                packageIds, quests, conditions, events, objectives, conversations,
                items, cancelers, schedules, functions, notifications, playerHiders, constants, metadata,
                narrativeSections);
        validate(loaded, errors);
        if (!errors.isEmpty()) {
            throw new IOException("MysticQuests package validation failed:\n - " + String.join("\n - ", errors));
        }
        return loaded;
    }

    private List<PackageRoot> discoverPackages(Path root) throws IOException {
        if (Files.notExists(root)) {
            return List.of();
        }
        try (var walk = Files.walk(root)) {
            return walk.filter(Files::isDirectory)
                    .filter(path -> !path.equals(root))
                    .filter(path -> hasManifest(path) || path.getParent().equals(root))
                    .map(path -> new PackageRoot(packageId(root, path), path))
                    .sorted(Comparator.comparing(PackageRoot::id))
                    .toList();
        }
    }

    private boolean hasManifest(Path directory) {
        return Files.isRegularFile(directory.resolve("package.yml"))
                || Files.isRegularFile(directory.resolve("package.yaml"))
                || Files.isRegularFile(directory.resolve("package.json"));
    }

    private String packageId(Path root, Path directory) {
        return root.relativize(directory).toString().replace('\\', '/');
    }

    private PackageManifest manifest(Path directory) throws IOException {
        Path file = manifestFile(directory);
        if (file == null) {
            return PackageManifest.defaults();
        }
        JsonNode document = readDocument(file);
        JsonNode node = document.has("package") ? document.get("package") : document;
        boolean enabled = !node.has("enabled") || node.path("enabled").asBoolean(true);
        String version = node.path("version").asText("");
        List<String> templates = new ArrayList<>();
        JsonNode values = node.get("templates");
        if (values != null && values.isArray()) {
            values.forEach(value -> templates.add(value.asText()));
        } else if (values != null && values.isTextual()) {
            for (String value : values.asText().split(",")) {
                if (!value.isBlank()) {
                    templates.add(value.trim());
                }
            }
        }
        return new PackageManifest(enabled, version, List.copyOf(templates));
    }

    private Path manifestFile(Path directory) {
        for (String name : List.of("package.yml", "package.yaml", "package.json")) {
            Path file = directory.resolve(name);
            if (Files.isRegularFile(file)) {
                return file;
            }
        }
        return null;
    }

    private void mergeTemplate(
            ObjectNode target,
            String templateId,
            Map<String, PackageRoot> templates,
            List<PackageRoot> allTemplates,
            Set<String> stack,
            List<String> errors) throws IOException {
        PackageRoot template = templates.get(templateId);
        if (template == null) {
            errors.add("Unknown package template: " + templateId);
            return;
        }
        if (!stack.add(templateId)) {
            errors.add("Package template cycle: " + String.join(" -> ", stack) + " -> " + templateId);
            return;
        }
        PackageManifest manifest = manifest(template.directory());
        for (String parent : manifest.templates()) {
            mergeTemplate(target, parent, templates, allTemplates, stack, errors);
        }
        mergeDocument(target, readPackageDocuments(template, allTemplates), false);
        stack.remove(templateId);
    }

    private ObjectNode readPackageDocuments(PackageRoot root, List<PackageRoot> allRoots) throws IOException {
        ObjectNode merged = mapper.createObjectNode();
        try (var walk = Files.walk(root.directory())) {
            for (Path file : walk.filter(Files::isRegularFile)
                    .filter(this::isContentFile)
                    .filter(path -> !insideOtherPackage(path, root, allRoots))
                    .sorted()
                    .toList()) {
                JsonNode document = readDocument(file);
                if (document == null || document.isNull()) {
                    continue;
                }
                if (document.isArray()) {
                    String section = sectionFromFilename(file);
                    if (section != null) {
                        ObjectNode wrapper = mapper.createObjectNode();
                        wrapper.set(section, document);
                        mergeDocument(merged, wrapper, true);
                    }
                } else if (document.isObject()) {
                    ObjectNode copy = ((ObjectNode) document).deepCopy();
                    copy.remove("package");
                    mergeDocument(merged, copy, true);
                }
            }
        }
        return merged;
    }

    private boolean insideOtherPackage(Path file, PackageRoot current, List<PackageRoot> roots) {
        return roots.stream().anyMatch(candidate -> !candidate.directory().equals(current.directory())
                && candidate.directory().startsWith(current.directory())
                && file.startsWith(candidate.directory()));
    }

    private boolean isContentFile(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        return (name.endsWith(".json") || name.endsWith(".yml") || name.endsWith(".yaml"))
                && !name.contains(".mq-staging") && !name.contains(".mq-rollback");
    }

    private JsonNode readDocument(Path file) throws IOException {
        String name = file.getFileName().toString().toLowerCase();
        return (name.endsWith(".yml") || name.endsWith(".yaml") ? yamlMapper : mapper).readTree(file.toFile());
    }

    private String sectionFromFilename(Path file) {
        String name = file.getFileName().toString().toLowerCase();
        int extension = name.lastIndexOf('.');
        String base = extension < 0 ? name : name.substring(0, extension);
        return switch (base) {
            case "quests", "conditions", "events", "actions", "objectives", "conversations",
                    "items", "cancelers", "cancel", "schedules", "functions", "notifications", "constants",
                    "puzzles", "overlays", "speakers", "media", "cutscenes" -> base;
            case "variableschemas" -> "variableSchemas";
            case "tagschemas" -> "tagSchemas";
            default -> null;
        };
    }

    private void mergeDocument(ObjectNode target, ObjectNode source, boolean override) {
        for (Map.Entry<String, JsonNode> entry : source.properties()) {
            JsonNode existing = target.get(entry.getKey());
            JsonNode incoming = entry.getValue();
            if (existing == null) {
                target.set(entry.getKey(), incoming.deepCopy());
            } else if (existing.isObject() && incoming.isObject()) {
                mergeDocument((ObjectNode) existing, (ObjectNode) incoming, override);
            } else if (existing.isArray() && incoming.isArray()) {
                mergeArray((ArrayNode) existing, (ArrayNode) incoming, override);
            } else if (override) {
                target.set(entry.getKey(), incoming.deepCopy());
            }
        }
    }

    private void mergeArray(ArrayNode target, ArrayNode source, boolean override) {
        for (JsonNode incoming : source) {
            String id = incoming.isObject() ? incoming.path("id").asText("") : "";
            int existingIndex = -1;
            if (!id.isBlank()) {
                for (int index = 0; index < target.size(); index++) {
                    if (id.equals(target.get(index).path("id").asText(""))) {
                        existingIndex = index;
                        break;
                    }
                }
            }
            if (existingIndex < 0) {
                target.add(incoming.deepCopy());
            } else if (override) {
                target.set(existingIndex, incoming.deepCopy());
            }
        }
    }

    private <T extends TypedConfig> void readTypedSection(
            JsonNode section,
            String packageId,
            Map<String, T> target,
            List<String> errors,
            Path source,
            TypedParser<T> parser) {
        if (section == null || section.isNull()) {
            return;
        }
        forEachNamed(section, (id, node) -> {
            try {
                T value = parser.parse(id, node);
                if (value.data().get("id") == null) {
                    value.put("id", mapper.getNodeFactory().textNode(id));
                }
                putUnique(packageId + ":" + id, value, target, errors, source);
            } catch (IOException | IllegalArgumentException exception) {
                errors.add(source + " has invalid " + id + ": " + exception.getMessage());
            }
        }, errors, source);
    }

    private ConditionDefinition parseCondition(String id, JsonNode node) throws IOException {
        ConditionDefinition definition = node.isTextual()
                ? instructions.condition(node.asText())
                : mapper.convertValue(node, ConditionDefinition.class);
        if (node.isTextual()) {
            definition.put("packageScoped", mapper.getNodeFactory().booleanNode(true));
        }
        ContentSchema.canonicalize(definition, ContentSchema.Kind.CONDITION);
        return definition;
    }

    private EventDefinition parseEvent(String id, JsonNode node) throws IOException {
        EventDefinition definition = node.isTextual()
                ? instructions.event(node.asText())
                : mapper.convertValue(node, EventDefinition.class);
        if (node.isTextual()) {
            definition.put("packageScoped", mapper.getNodeFactory().booleanNode(true));
        }
        ContentSchema.canonicalize(definition, ContentSchema.Kind.EVENT);
        return definition;
    }

    private ObjectiveDefinition parseObjective(String id, JsonNode node) throws IOException {
        if (node.isTextual()) {
            return instructions.objective(id, node.asText());
        }
        ObjectNode copy = ((ObjectNode) node).deepCopy();
        copy.putIfAbsent("id", mapper.getNodeFactory().textNode(id));
        return mapper.convertValue(copy, ObjectiveDefinition.class);
    }

    private void readQuestSection(JsonNode section, String packageId, Map<String, QuestDefinition> target, List<String> errors, Path source) {
        if (section == null || section.isNull()) {
            return;
        }
        forEachNamed(section, (id, node) -> {
            if (!node.isObject()) {
                errors.add(source + " quest " + id + " must be an object");
                return;
            }
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.putIfAbsent("id", mapper.getNodeFactory().textNode(id));
            normalizeReferenceArrays(copy);
            // A quest's inline event and condition lists never pass through parseEvent/parseCondition,
            // so they are rewritten here before Jackson binds them.
            ContentSchema.canonicalizeTree(copy, ContentSchema.Kind.EVENT);
            QuestDefinition quest = mapper.convertValue(copy, QuestDefinition.class);
            quest.setPackageId(packageId);
            putUnique(packageId + ":" + quest.id(), quest, target, errors, source);
        }, errors, source);
    }

    private void readConversationSection(JsonNode section, String packageId, Map<String, ConversationDefinition> target, List<String> errors, Path source) {
        if (section == null || section.isNull()) {
            return;
        }
        forEachNamed(section, (id, node) -> {
            if (!node.isObject()) {
                errors.add(source + " conversation " + id + " must be an object");
                return;
            }
            ObjectNode copy = ((ObjectNode) node).deepCopy();
            copy.putIfAbsent("id", mapper.getNodeFactory().textNode(id));
            ContentSchema.canonicalizeTree(copy, ContentSchema.Kind.EVENT);
            ConversationDefinition conversation = mapper.convertValue(copy, ConversationDefinition.class);
            conversation.setPackageId(packageId);
            putUnique(packageId + ":" + conversation.id(), conversation, target, errors, source);
        }, errors, source);
    }

    private void readRawSection(JsonNode section, String packageId, Map<String, JsonNode> target, List<String> errors, Path source) {
        if (section == null || section.isNull()) {
            return;
        }
        forEachNamed(section, (id, node) -> putUnique(packageId + ":" + id, node.deepCopy(), target, errors, source), errors, source);
    }

    private void forEachNamed(JsonNode section, BiConsumer<String, JsonNode> consumer, List<String> errors, Path source) {
        if (section.isObject()) {
            section.properties().forEach(entry -> consumer.accept(entry.getKey(), entry.getValue()));
            return;
        }
        if (section.isArray()) {
            for (JsonNode node : section) {
                String id = node.path("id").asText("");
                if (id.isBlank()) {
                    errors.add(source + " contains a list entry without id");
                } else {
                    consumer.accept(id, node);
                }
            }
            return;
        }
        errors.add(source + " section must be an object or array");
    }

    private void normalizeReferenceArrays(ObjectNode quest) {
        for (String field : List.of("startConditions", "startEvents", "completeEvents", "rewards", "reacceptConditions")) {
            JsonNode values = quest.get(field);
            if (values != null && values.isArray()) {
                ArrayNode normalized = mapper.createArrayNode();
                for (JsonNode value : values) {
                    if (value.isTextual()) {
                        ObjectNode reference = mapper.createObjectNode();
                        reference.put("type", "ref");
                        reference.put("id", value.asText());
                        normalized.add(reference);
                    } else {
                        normalized.add(value);
                    }
                }
                quest.set(field, normalized);
            }
        }
        // "stages": ["discover", "keepers"] is the short form of the same list of {"id": …} objects,
        // for authors who only need the order and are happy with names derived from the ids.
        JsonNode stageValues = quest.get("stages");
        if (stageValues != null && stageValues.isArray()) {
            ArrayNode normalized = mapper.createArrayNode();
            for (JsonNode value : stageValues) {
                if (value.isTextual()) {
                    ObjectNode stage = mapper.createObjectNode();
                    stage.put("id", value.asText());
                    normalized.add(stage);
                } else {
                    normalized.add(value);
                }
            }
            quest.set("stages", normalized);
        }
        JsonNode objectiveValues = quest.get("objectives");
        if (objectiveValues != null && objectiveValues.isArray()) {
            ArrayNode normalized = mapper.createArrayNode();
            for (JsonNode value : objectiveValues) {
                if (value.isTextual()) {
                    ObjectNode reference = mapper.createObjectNode();
                    reference.put("id", localReferenceId(value.asText()));
                    reference.put("type", "ref");
                    reference.put("ref", value.asText());
                    normalized.add(reference);
                } else {
                    normalized.add(value);
                }
            }
            quest.set("objectives", normalized);
        }
    }

    private void resolveObjectiveReferences(
            Map<String, QuestDefinition> quests,
            Map<String, ObjectiveDefinition> definitions,
            List<String> errors) {
        for (QuestDefinition quest : quests.values()) {
            List<ObjectiveDefinition> resolved = new ArrayList<>();
            for (ObjectiveDefinition objective : quest.objectives()) {
                if (!"ref".equals(objective.type())) {
                    resolved.add(objective);
                    continue;
                }
                String raw = objective.text("ref", objective.text("id", ""));
                String key = resolveReference(quest.packageId(), raw);
                ObjectiveDefinition shared = definitions.get(key);
                if (shared == null) {
                    errors.add(quest.packageId() + ":" + quest.id() + " references unknown objective " + raw);
                    continue;
                }
                ObjectiveDefinition copy = mapper.convertValue(shared, ObjectiveDefinition.class);
                copy.setId(objective.id() == null || objective.id().isBlank() ? shared.id() : objective.id());
                resolved.add(copy);
            }
            quest.setObjectives(resolved);
        }
    }

    private String resolveReference(String packageId, String raw) {
        if (raw == null || raw.isBlank()) {
            return raw;
        }
        if (raw.contains(":")) {
            return raw;
        }
        int separator = raw.indexOf('>');
        if (separator > 0) {
            return raw.substring(0, separator).replace('-', '/') + ":" + raw.substring(separator + 1);
        }
        return packageId + ":" + raw;
    }

    private String localReferenceId(String raw) {
        int separator = Math.max(raw.lastIndexOf('>'), raw.lastIndexOf(':'));
        return separator < 0 ? raw : raw.substring(separator + 1);
    }

    private JsonNode first(ObjectNode root, String first, String second) {
        return root.has(first) ? root.get(first) : root.get(second);
    }

    @FunctionalInterface
    private interface TypedParser<T extends TypedConfig> {
        T parse(String id, JsonNode node) throws IOException;
    }

    private record PackageRoot(String id, Path directory) { }

    private record PackageManifest(boolean enabled, String version, List<String> templates) {
        static PackageManifest defaults() {
            return new PackageManifest(true, "", List.of());
        }
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
            validateTypedList(entry.getKey(), "reaccept condition", quest.reacceptConditions(), CONDITION_TYPES, errors);
            validateStages(entry.getKey(), quest, errors);
            validateObjectives(entry.getKey(), quest.objectives(), errors);
            if (quest.objectives().isEmpty()) {
                errors.add(entry.getKey() + " must declare at least one objective");
            }
            Integer cooldown = quest.abandonCooldownSeconds();
            if (cooldown != null && cooldown < 0) {
                errors.add(entry.getKey() + " has a negative abandonCooldownSeconds: " + cooldown);
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
        validateReferences(content, errors);
    }

    private void validateReferences(LoadedContent content, List<String> errors) {
        for (Map.Entry<String, QuestDefinition> entry : content.quests().entrySet()) {
            String packageId = packageFrom(entry.getKey());
            QuestDefinition quest = entry.getValue();
            validateConditionReferences(entry.getKey(), packageId, quest.startConditions(), content, errors);
            validateConditionReferences(entry.getKey(), packageId, quest.reacceptConditions(), content, errors);
            validateEventReferences(entry.getKey(), packageId, quest.startEvents(), content, errors);
            validateEventReferences(entry.getKey(), packageId, quest.completeEvents(), content, errors);
            validateEventReferences(entry.getKey(), packageId, quest.rewards(), content, errors);
        }
        for (Map.Entry<String, ConditionDefinition> entry : content.conditions().entrySet()) {
            validateConditionReferences(entry.getKey(), packageFrom(entry.getKey()), List.of(entry.getValue()), content, errors);
        }
        for (Map.Entry<String, EventDefinition> entry : content.events().entrySet()) {
            validateEventReferences(entry.getKey(), packageFrom(entry.getKey()), List.of(entry.getValue()), content, errors);
        }
    }

    private void validateConditionReferences(
            String owner,
            String packageId,
            List<ConditionDefinition> values,
            LoadedContent content,
            List<String> errors) {
        for (ConditionDefinition value : values) {
            if ("ref".equals(value.type())) {
                String raw = value.text("id", value.text("ref", ""));
                String key = content.resolveId(packageId, raw);
                if (!content.conditions().containsKey(key)) {
                    errors.add(owner + " references unknown condition " + raw);
                }
            } else if (COMPOSITE_CONDITION_TYPES.contains(value.type())) {
                validateConditionReferences(owner, packageId, value.children("conditions", ConditionDefinition::new), content, errors);
                validateConditionReferences(owner, packageId, value.children("condition", ConditionDefinition::new), content, errors);
            }
        }
    }

    private void validateEventReferences(
            String owner,
            String packageId,
            List<EventDefinition> values,
            LoadedContent content,
            List<String> errors) {
        for (EventDefinition value : values) {
            if ("ref".equals(value.type())) {
                String raw = value.text("id", value.text("ref", ""));
                String key = content.resolveId(packageId, raw);
                if (!content.events().containsKey(key)) {
                    errors.add(owner + " references unknown event " + raw);
                }
            } else if ("folder".equals(value.type())) {
                validateEventReferences(owner, packageId, value.children("events", EventDefinition::new), content, errors);
            } else if ("if".equals(value.type())) {
                validateConditionReferences(owner, packageId, value.children("conditions", ConditionDefinition::new), content, errors);
                validateConditionReferences(owner, packageId, value.children("condition", ConditionDefinition::new), content, errors);
                validateEventReferences(owner, packageId, value.children("then", EventDefinition::new), content, errors);
                validateEventReferences(owner, packageId, value.children("else", EventDefinition::new), content, errors);
            }
        }
    }

    private String packageFrom(String namespacedId) {
        int separator = namespacedId.lastIndexOf(':');
        return separator < 0 ? namespacedId : namespacedId.substring(0, separator);
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
        // Every candidate, not just the first: a typo in a later entry point would otherwise surface
        // only as an NPC that silently declines to talk, once the earlier ones stop passing.
        for (String start : conversation.startCandidates()) {
            if (start != null && !nodeIds.contains(start)) {
                errors.add(conversationId + " start node does not exist: " + start);
            }
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

    /**
     * Steps are optional, and objectives may name a stage no {@code stages} entry describes — that
     * is the whole point of deriving steps from the objectives. Once a quest does declare stages
     * though, an objective naming one that is not in the list is a typo, and a typo would silently
     * split the quest into an extra step nobody wrote.
     */
    private void validateStages(String questId, QuestDefinition quest, List<String> errors) {
        Set<String> declared = new LinkedHashSet<>();
        for (StageDefinition stage : quest.stages()) {
            String stageId = stage.id() == null ? "" : stage.id().trim();
            if (stageId.isEmpty()) {
                errors.add(questId + " contains a stage without id");
            } else if (!declared.add(stageId)) {
                errors.add(questId + " duplicates stage id " + stageId);
            }
        }
        if (declared.isEmpty()) {
            return;
        }
        for (ObjectiveDefinition objective : quest.objectives()) {
            String stageId = objective.stage();
            if (!stageId.isEmpty() && !declared.contains(stageId)) {
                errors.add(questId + "/" + objective.id() + " names undeclared stage " + stageId);
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
            if ("signal".equals(objective.type()) && objective.text("signal", objective.text("target", "")).isBlank()) {
                errors.add(questId + "/" + objective.id() + " is a signal objective that names no \"signal\"");
            }
            try {
                ObjectiveMarker.parse(objective.data().get("marker"));
            } catch (IllegalArgumentException malformed) {
                errors.add(questId + "/" + objective.id() + " has an invalid marker: " + malformed.getMessage());
            }
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
        String type = value.type();
        if (type == null || type.isBlank()) {
            errors.add(owner + " contains " + label + " without type");
            return;
        }
        Map<String, String> unsupportedForKind = switch (kind(allowedTypes)) {
            case CONDITION -> UNSUPPORTED_CONDITION_TYPES;
            case EVENT -> UNSUPPORTED_EVENT_TYPES;
            case OBJECTIVE -> Map.of();
        };
        String unsupported = unsupportedForKind.get(type);
        if (unsupported != null) {
            errors.add(owner + " uses unsupported " + label + " type '" + type + "': " + unsupported);
            return;
        }
        if (!allowedTypes.contains(type)) {
            errors.add(owner + " uses unknown " + label + " type " + type);
            return;
        }
        if (value instanceof ConditionDefinition condition && COMPOSITE_CONDITION_TYPES.contains(type)) {
            validateComposite(owner, label, condition, errors);
        } else if (value instanceof EventDefinition event) {
            validateNestedEvents(owner, label, event, errors);
        }
    }

    private Kind kind(Set<String> allowedTypes) {
        if (allowedTypes.equals(CONDITION_TYPES)) {
            return Kind.CONDITION;
        }
        return allowedTypes.equals(EVENT_TYPES) ? Kind.EVENT : Kind.OBJECTIVE;
    }

    private enum Kind {
        CONDITION,
        EVENT,
        OBJECTIVE
    }

    /** A composite condition that declares no children would otherwise evaluate to a constant. */
    private void validateComposite(String owner, String label, ConditionDefinition condition, List<String> errors) {
        List<ConditionDefinition> children = condition.children("conditions", ConditionDefinition::new);
        if (children.isEmpty()) {
            children = condition.children("condition", ConditionDefinition::new);
        }
        if (children.isEmpty()) {
            errors.add(owner + " " + label + " '" + condition.type() + "' declares no nested conditions");
            return;
        }
        validateTypedList(owner + "/" + condition.type(), label, children, CONDITION_TYPES, errors);
    }

    private void validateNestedEvents(String owner, String label, EventDefinition event, List<String> errors) {
        if (event.type().equals("folder") || event.type().equals("party")) {
            List<EventDefinition> children = event.children("events", EventDefinition::new);
            if (children.isEmpty()) {
                errors.add(owner + " " + label + " '" + event.type() + "' declares no nested events");
            }
            validateTypedList(owner + "/" + event.type(), label, children, EVENT_TYPES, errors);
        } else if (event.type().equals("if")) {
            List<ConditionDefinition> conditions = event.children("conditions", ConditionDefinition::new);
            if (conditions.isEmpty()) {
                conditions = event.children("condition", ConditionDefinition::new);
            }
            if (conditions.isEmpty()) {
                errors.add(owner + " " + label + " 'if' declares no conditions");
            }
            validateTypedList(owner + "/if", "condition", conditions, CONDITION_TYPES, errors);
            validateTypedList(owner + "/if/then", label, event.children("then", EventDefinition::new), EVENT_TYPES, errors);
            validateTypedList(owner + "/if/else", label, event.children("else", EventDefinition::new), EVENT_TYPES, errors);
        }
    }
}
