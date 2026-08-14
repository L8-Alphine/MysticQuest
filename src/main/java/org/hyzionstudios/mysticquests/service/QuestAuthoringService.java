package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ArrayNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import java.io.IOException;
import java.nio.file.AtomicMoveNotSupportedException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.function.Supplier;

/** Transactional persistence for the in-game quest studio. */
public final class QuestAuthoringService {
    private static final Set<String> OBJECTIVE_TYPES = Set.of(
            "kill", "gather", "craft", "triggerEnter", "triggerExit", "interactEntity",
            "interactObject", "reachLocation", "dialogue", "timer", "custom");

    private final Path packagesDirectory;
    private final ObjectMapper mapper;
    private final ObjectMapper yamlMapper;
    private final Supplier<LoadedContent> contentSupplier;
    private final ReloadAction reloadAction;

    public QuestAuthoringService(
            Path packagesDirectory,
            ObjectMapper mapper,
            Supplier<LoadedContent> contentSupplier,
            ReloadAction reloadAction) {
        this.packagesDirectory = packagesDirectory;
        this.mapper = mapper;
        this.yamlMapper = Json.createYamlMapper();
        this.contentSupplier = contentSupplier;
        this.reloadAction = reloadAction;
    }

    /** Loads an arbitrary JSON/YAML package source file for the Studio script editor. */
    public String loadSource(String packageId, String fileName) throws IOException {
        Path file = sourcePath(packageId, fileName);
        if (!Files.exists(file)) {
            return file.getFileName().toString().startsWith("package.")
                    ? "enabled: true\nversion: 1\ntemplates: []\n"
                    : "# Named scripting elements\nconditions: {}\nactions: {}\nobjectives: {}\nquests: []\n";
        }
        return Files.readString(file);
    }

    /** Atomically validates, publishes, reloads, and rolls back a source file on any error. */
    public SourceSaveResult saveSource(String packageId, String fileName, String source) throws IOException {
        Path file = sourcePath(packageId, fileName);
        String content = source == null ? "" : source;
        if (content.isBlank()) {
            throw new IOException("Script source cannot be empty.");
        }
        ObjectMapper sourceMapper = file.getFileName().toString().endsWith(".json") ? mapper : yamlMapper;
        try {
            JsonNode parsed = sourceMapper.readTree(content);
            if (parsed == null || !parsed.isContainerNode()) {
                throw new IOException("Script source must contain a YAML/JSON object or array.");
            }
        } catch (com.fasterxml.jackson.core.JacksonException exception) {
            throw new IOException("Invalid YAML/JSON: " + exception.getOriginalMessage(), exception);
        }

        Files.createDirectories(file.getParent());
        byte[] original = Files.exists(file) ? Files.readAllBytes(file) : null;
        Path temporary = file.resolveSibling(file.getFileName() + ".mq-staging");
        Files.writeString(temporary, content);
        replace(temporary, file);
        try {
            LoadedContent loaded = reloadAction.reload();
            return new SourceSaveResult(file, loaded.packages().size(), loaded.quests().size());
        } catch (IOException | RuntimeException validationFailure) {
            rollback(file, original);
            try {
                reloadAction.reload();
            } catch (Exception restoreFailure) {
                validationFailure.addSuppressed(restoreFailure);
            }
            throw new IOException("Script was not saved: " + validationFailure.getMessage(), validationFailure);
        }
    }

    private Path sourcePath(String packageId, String fileName) throws IOException {
        String normalizedPackage = validateId(packageId, "Package ID");
        String normalizedFile = fileName == null ? "" : fileName.trim().replace('\\', '/').toLowerCase();
        if (!normalizedFile.matches("[a-z0-9][a-z0-9_./-]*\\.(json|ya?ml)")
                || normalizedFile.contains("..") || normalizedFile.startsWith("/")) {
            throw new IOException("File must be a relative .json, .yml, or .yaml package path.");
        }
        Path packageDirectory = packagesDirectory.resolve(normalizedPackage).normalize();
        Path file = packageDirectory.resolve(normalizedFile).normalize();
        if (!file.startsWith(packageDirectory)) {
            throw new IOException("Script path escapes its package.");
        }
        return file;
    }

    public QuestDraft load(String packageId, String questId) throws IOException {
        String normalizedPackage = validateId(packageId, "Package ID");
        String normalizedQuest = validateId(questId, "Quest ID");
        QuestDefinition quest = contentSupplier.get().quests().get(normalizedPackage + ":" + normalizedQuest);
        if (quest == null) {
            throw new IOException("Unknown quest: " + normalizedPackage + ":" + normalizedQuest);
        }
        List<ObjectiveDraft> objectives = quest.objectives().stream().map(objective -> new ObjectiveDraft(
                objective.id(),
                objective.type(),
                objective.displayName(),
                objective.text("target", objective.text("entity", objective.text("item", objective.text("volume", "")))),
                objective.integer("amount", 1))).toList();
        List<ObjectiveDefinition> extraObjectives = quest.objectives().size() <= 4
                ? List.of()
                : quest.objectives().subList(4, quest.objectives().size());
        return new QuestDraft(
                normalizedPackage,
                normalizedQuest,
                quest.displayName(),
                quest.description(),
                quest.startOnJoin(),
                quest.abandonCooldownSeconds() == null ? "" : quest.abandonCooldownSeconds().toString(),
                objectives.stream().limit(4).toList(),
                pretty(extraObjectives),
                pretty(quest.startConditions()),
                pretty(quest.startEvents()),
                pretty(quest.completeEvents()),
                pretty(quest.rewards()),
                pretty(quest.reacceptConditions()));
    }

    public SaveResult save(QuestDraft draft) throws IOException {
        String packageId = validateId(draft.packageId(), "Package ID");
        String questId = validateId(draft.questId(), "Quest ID");
        if (draft.title() == null || draft.title().isBlank()) {
            throw new IOException("Quest title is required.");
        }
        QuestDefinition quest = buildQuest(draft, questId);

        Path packageDirectory = packagesDirectory.resolve(packageId).normalize();
        if (!packageDirectory.startsWith(packagesDirectory.normalize())) {
            throw new IOException("Package path escapes the configured packages directory.");
        }
        Files.createDirectories(packageDirectory);
        Path questsFile = packageDirectory.resolve("quests.json");
        byte[] original = Files.exists(questsFile) ? Files.readAllBytes(questsFile) : null;
        JsonNode originalRoot = original == null ? null : mapper.readTree(original);
        boolean arrayRoot = originalRoot != null && originalRoot.isArray();
        ArrayNode quests = existingQuests(originalRoot);

        for (int index = quests.size() - 1; index >= 0; index--) {
            if (questId.equals(quests.path(index).path("id").asText())) {
                quests.remove(index);
            }
        }
        ObjectNode questNode = mapper.valueToTree(quest);
        questNode.remove("packageId");
        quests.add(questNode);

        JsonNode output;
        if (arrayRoot) {
            output = quests;
        } else {
            ObjectNode wrapper = originalRoot != null && originalRoot.isObject()
                    ? ((ObjectNode) originalRoot).deepCopy()
                    : mapper.createObjectNode();
            wrapper.set("quests", quests);
            output = wrapper;
        }

        Path temporary = packageDirectory.resolve("quests.json.mq-staging");
        mapper.writerWithDefaultPrettyPrinter().writeValue(temporary.toFile(), output);
        replace(temporary, questsFile);
        try {
            LoadedContent loaded = reloadAction.reload();
            return new SaveResult(packageId + ":" + questId, questsFile, loaded.quests().size());
        } catch (IOException | RuntimeException validationFailure) {
            rollback(questsFile, original);
            try {
                reloadAction.reload();
            } catch (Exception restoreFailure) {
                validationFailure.addSuppressed(restoreFailure);
            }
            throw new IOException("Quest was not saved: " + validationFailure.getMessage(), validationFailure);
        }
    }

    private QuestDefinition buildQuest(QuestDraft draft, String questId) throws IOException {
        QuestDefinition quest = new QuestDefinition();
        quest.setId(questId);
        quest.setDisplayName(draft.title().trim());
        quest.setDescription(draft.description() == null ? "" : draft.description().trim());
        quest.setStartOnJoin(draft.startOnJoin());
        if (draft.cooldownSeconds() != null && !draft.cooldownSeconds().isBlank()) {
            try {
                int cooldown = Integer.parseInt(draft.cooldownSeconds().trim());
                if (cooldown < 0) {
                    throw new NumberFormatException();
                }
                quest.setAbandonCooldownSeconds(cooldown);
            } catch (NumberFormatException exception) {
                throw new IOException("Re-accept cooldown must be a non-negative whole number.");
            }
        }

        List<ObjectiveDefinition> objectives = new ArrayList<>();
        for (ObjectiveDraft source : draft.objectives()) {
            if (source == null || allBlank(source.id(), source.type(), source.title(), source.target())) {
                continue;
            }
            String objectiveId = validateId(source.id(), "Objective ID");
            String type = source.type() == null ? "" : source.type().trim();
            if (!OBJECTIVE_TYPES.contains(type)) {
                throw new IOException("Unsupported objective type '" + type + "'. Supported: " + OBJECTIVE_TYPES);
            }
            ObjectiveDefinition objective = new ObjectiveDefinition();
            objective.setId(objectiveId);
            objective.setType(type);
            objective.setDisplayName(source.title() == null || source.title().isBlank() ? objectiveId : source.title().trim());
            if (source.target() != null && !source.target().isBlank()) {
                objective.put("target", TextNode.valueOf(source.target().trim()));
            }
            objective.put("amount", mapper.getNodeFactory().numberNode(Math.max(1, source.amount())));
            objectives.add(objective);
        }
        objectives.addAll(parseList(
                draft.extraObjectivesJson(), new TypeReference<List<ObjectiveDefinition>>() {}, "Additional objectives"));
        if (objectives.isEmpty()) {
            throw new IOException("Add at least one objective before publishing.");
        }
        quest.setObjectives(objectives);
        quest.setStartConditions(parseList(draft.startConditionsJson(), new TypeReference<>() {}, "Start conditions"));
        quest.setStartEvents(parseList(draft.startEventsJson(), new TypeReference<>() {}, "Start events"));
        quest.setCompleteEvents(parseList(draft.completeEventsJson(), new TypeReference<>() {}, "Complete events"));
        quest.setRewards(parseList(draft.rewardsJson(), new TypeReference<>() {}, "Rewards"));
        quest.setReacceptConditions(parseList(draft.reacceptConditionsJson(), new TypeReference<>() {}, "Re-accept conditions"));
        return quest;
    }

    private <T> List<T> parseList(String raw, TypeReference<List<T>> type, String label) throws IOException {
        if (raw == null || raw.isBlank()) {
            return List.of();
        }
        try {
            JsonNode root = mapper.readTree(raw);
            if (!root.isArray()) {
                throw new IOException(label + " must be a JSON array.");
            }
            return mapper.convertValue(root, type);
        } catch (IllegalArgumentException | com.fasterxml.jackson.core.JacksonException exception) {
            throw new IOException(label + " contain invalid JSON: " + exception.getMessage(), exception);
        }
    }

    private ArrayNode existingQuests(JsonNode root) throws IOException {
        if (root == null) {
            return mapper.createArrayNode();
        }
        JsonNode list = root.isArray() ? root : root.get("quests");
        if (list == null || !list.isArray()) {
            throw new IOException("quests.json must be an array or contain a quests array.");
        }
        return ((ArrayNode) list).deepCopy();
    }

    private String validateId(String value, String label) throws IOException {
        String normalized = value == null ? "" : value.trim().toLowerCase();
        if (!normalized.matches("[a-z0-9][a-z0-9_-]*")) {
            throw new IOException(label + " must use lowercase letters, numbers, underscores, or hyphens.");
        }
        return normalized;
    }

    private boolean allBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return false;
            }
        }
        return true;
    }

    private String pretty(Object value) throws IOException {
        return mapper.writerWithDefaultPrettyPrinter().writeValueAsString(value);
    }

    private void rollback(Path target, byte[] original) throws IOException {
        if (original == null) {
            Files.deleteIfExists(target);
            return;
        }
        Path rollback = target.resolveSibling(target.getFileName() + ".mq-rollback");
        Files.write(rollback, original);
        replace(rollback, target);
    }

    private void replace(Path source, Path target) throws IOException {
        try {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.ATOMIC_MOVE);
        } catch (AtomicMoveNotSupportedException exception) {
            Files.move(source, target, StandardCopyOption.REPLACE_EXISTING);
        }
    }

    @FunctionalInterface
    public interface ReloadAction {
        LoadedContent reload() throws IOException;
    }

    public record ObjectiveDraft(String id, String type, String title, String target, int amount) {
    }

    public record QuestDraft(
            String packageId,
            String questId,
            String title,
            String description,
            boolean startOnJoin,
            String cooldownSeconds,
            List<ObjectiveDraft> objectives,
            String extraObjectivesJson,
            String startConditionsJson,
            String startEventsJson,
            String completeEventsJson,
            String rewardsJson,
            String reacceptConditionsJson) {
    }

    public record SaveResult(String questId, Path file, int loadedQuestCount) {
    }

    public record SourceSaveResult(Path file, int loadedPackageCount, int loadedQuestCount) {
    }
}
