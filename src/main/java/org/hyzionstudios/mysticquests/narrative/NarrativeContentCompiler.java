package org.hyzionstudios.mysticquests.narrative;

import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.action.builtin.CutsceneActions;
import org.hyzionstudios.mysticquests.narrative.action.builtin.MediaActions;
import org.hyzionstudios.mysticquests.narrative.cutscene.CutsceneCompiler;
import org.hyzionstudios.mysticquests.narrative.cutscene.CutsceneDefinition;
import org.hyzionstudios.mysticquests.narrative.action.builtin.PuzzleActions;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaAsset;
import org.hyzionstudios.mysticquests.narrative.media.MediaCatalog;
import org.hyzionstudios.mysticquests.narrative.media.MediaCompiler;
import org.hyzionstudios.mysticquests.narrative.media.Speaker;
import org.hyzionstudios.mysticquests.narrative.overlay.OverlayDefinition;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleCompiler;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition;
import org.hyzionstudios.mysticquests.narrative.state.SchemaRegistry;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.state.TagSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.time.Duration;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * Compiles every package's narrative sections into one {@link NarrativeContent}.
 *
 * <p>Sections, read from the same package files as quests:
 * <pre>
 *   variableSchemas: [ { "id": "hyzion:druid_temple.keys_found", "type": "integer", "scope": "quest_session", "default": 0 } ]
 *   tagSchemas:      [ { "id": "hyzion:druid_temple.entered", "scope": "player", "ttl": "1h" } ]
 *   puzzles:         [ { "id": "hyzion:druid_temple.hidden_keys", ... } ]
 * </pre>
 *
 * <p>Compilation is two-pass: every package's schemas first, then every package's puzzles against
 * the complete schema set. A puzzle in one package can therefore use a variable declared in another
 * regardless of load order. Any error fails the reload as a whole, and the previous content keeps
 * running, the same transactional rule the v1 loader follows.
 */
public final class NarrativeContentCompiler {
    private final ScopeSupport scopes;
    private final ConditionTypeRegistry conditions;
    private final ActionTypeRegistry actions;
    private final Collection<String> openNamespaces;
    private final MediaCatalog catalog;
    private final String fallbackLocale;

    public NarrativeContentCompiler(ScopeSupport scopes, ConditionTypeRegistry conditions, ActionTypeRegistry actions,
                                    Collection<String> openNamespaces) {
        this(scopes, conditions, actions, openNamespaces, MediaCatalog.UNKNOWN, "en-US");
    }

    /**
     * @param catalog asks the engine whether audio assets are loaded
     * @param fallbackLocale the locale every localised voice line must have a recording for
     */
    public NarrativeContentCompiler(ScopeSupport scopes, ConditionTypeRegistry conditions, ActionTypeRegistry actions,
                                    Collection<String> openNamespaces, MediaCatalog catalog, String fallbackLocale) {
        this.scopes = scopes;
        this.conditions = conditions;
        this.actions = actions;
        this.openNamespaces = List.copyOf(openNamespaces);
        this.catalog = catalog;
        this.fallbackLocale = fallbackLocale;
    }

    /**
     * @param sections per package id, an object holding whichever of the narrative sections the
     *         package defines
     * @param packageVersions per package id, its manifest version
     */
    public NarrativeContent compile(Map<String, JsonNode> sections, Map<String, String> packageVersions, DiagnosticReport report) {
        Map<NamespacedId, VariableSchema> variables = new LinkedHashMap<>();
        Map<NamespacedId, TagSchema> tags = new LinkedHashMap<>();
        sections.forEach((packageId, node) -> {
            forEachEntry(node.get("variableSchemas"), packageId + "/variableSchemas", report, (entry, path) ->
                    variable(entry, path, report, variables));
            forEachEntry(node.get("tagSchemas"), packageId + "/tagSchemas", report, (entry, path) ->
                    tag(entry, path, report, tags));
        });
        SchemaRegistry schemas = new SchemaRegistry(variables, tags, openNamespaces);
        CompileContext base = new CompileContext(schemas, scopes, conditions, actions, "");

        Map<NamespacedId, OverlayDefinition> overlays = new LinkedHashMap<>();
        sections.forEach((packageId, node) -> forEachEntry(node.get("overlays"), packageId + "/overlays", report,
                (entry, path) -> overlay(entry, path, packageId, report, overlays)));

        Map<NamespacedId, Speaker> speakers = new LinkedHashMap<>();
        Map<NamespacedId, MediaAsset> media = new LinkedHashMap<>();
        sections.forEach((packageId, node) -> {
            forEachEntry(node.get("speakers"), packageId + "/speakers", report,
                    (entry, path) -> MediaCompiler.speaker(entry, path, report, speakers));
            forEachEntry(node.get("media"), packageId + "/media", report,
                    (entry, path) -> MediaCompiler.asset(entry, path, packageId, catalog, fallbackLocale, report, media));
        });
        MediaCompiler.checkSpeakers(media, speakers, report);

        Map<NamespacedId, PuzzleDefinition> puzzles = new LinkedHashMap<>();
        sections.forEach((packageId, node) -> {
            CompileContext context = base.inPackage(packageId);
            forEachEntry(node.get("puzzles"), packageId + "/puzzles", report, (entry, path) -> {
                PuzzleDefinition puzzle = PuzzleCompiler.compile(entry, path, context, report);
                if (puzzle != null && puzzles.putIfAbsent(puzzle.id(), puzzle) != null) {
                    report.error(DiagnosticCode.DUPLICATE_ID, path, "puzzle " + puzzle.id() + " is defined twice");
                }
            });
        });
        Map<NamespacedId, CutsceneDefinition> cutscenes = new LinkedHashMap<>();
        sections.forEach((packageId, node) -> {
            CompileContext context = base.inPackage(packageId);
            forEachEntry(node.get("cutscenes"), packageId + "/cutscenes", report, (entry, path) -> {
                CutsceneDefinition cutscene = CutsceneCompiler.compile(entry, path, context, report);
                if (cutscene != null && cutscenes.putIfAbsent(cutscene.id(), cutscene) != null) {
                    report.error(DiagnosticCode.DUPLICATE_ID, path, "cutscene " + cutscene.id() + " is defined twice");
                }
            });
        });
        checkReferences(puzzles, overlays, media, cutscenes, report);

        Map<String, List<NarrativeContent.PuzzleBinding>> bindings = new LinkedHashMap<>();
        for (PuzzleDefinition puzzle : puzzles.values()) {
            for (PuzzleDefinition.Input input : puzzle.inputs()) {
                if (input.volume() != null) {
                    bindings.computeIfAbsent(input.volume(), ignored -> new ArrayList<>())
                            .add(new NarrativeContent.PuzzleBinding(puzzle.id(), input.id(), input.event(), input.toggleable()));
                }
            }
        }
        bindings.replaceAll((volume, list) -> List.copyOf(list));
        return new NarrativeContent(schemas, puzzles, overlays, speakers, media, cutscenes, bindings, packageVersions, report);
    }

    /**
     * Reads one overlay. Only entity overlays exist: the engine cannot give one player a block the
     * server does not have without block effects diverging, so a block overlay is refused with the
     * entity pattern as the fix (see {@code WorldOverlayRegistry}).
     */
    private void overlay(JsonNode entry, String path, String packageId, DiagnosticReport report,
                         Map<NamespacedId, OverlayDefinition> into) {
        NamespacedId id = ContentParams.id(entry, "id", path, report);
        if (id == null) {
            return;
        }
        String overlayPath = path + "<" + id + ">";
        String kind = entry.path("kind").asText("entity");
        if (!kind.equals("entity")) {
            report.error(DiagnosticCode.INVALID_PARAMETER, overlayPath, "overlay kind '" + kind + "' is not supported",
                    "place an entity with HardCollision (Hitbox editor tool) and use kind entity; per-player block changes are not available on this engine");
            return;
        }
        Coercion entity = ValueCodec.coerce(TypeSpec.of(ValueType.ENTITY_REFERENCE), entry.get("entity"));
        if (!entity.accepted() || !(entity.value() instanceof QuestValue.EntityRefValue ref)
                || !(ref.kind().equals("uuid") || ref.kind().equals("generation"))) {
            report.error(DiagnosticCode.UNKNOWN_ENTITY, overlayPath, "\"entity\" must be uuid:<entity uuid> or generation:<stable id>");
            return;
        }
        if (ref.kind().equals("uuid")) {
            try {
                UUID.fromString(ref.id());
            } catch (IllegalArgumentException invalid) {
                report.error(DiagnosticCode.UNKNOWN_ENTITY, overlayPath, "\"" + ref.id() + "\" is not a UUID");
                return;
            }
        }
        String presence = entry.path("default").asText("present");
        if (!presence.equals("present") && !presence.equals("absent")) {
            report.error(DiagnosticCode.INVALID_PARAMETER, overlayPath, "\"default\" must be present or absent");
            return;
        }
        OverlayDefinition overlay = new OverlayDefinition(id, packageId, ref, presence.equals("present"),
                entry.path("description").asText(""));
        if (into.putIfAbsent(id, overlay) != null) {
            report.error(DiagnosticCode.DUPLICATE_ID, overlayPath, "overlay " + id + " is defined twice");
        }
    }

    private void variable(JsonNode entry, String path, DiagnosticReport report, Map<NamespacedId, VariableSchema> into) {
        NamespacedId id = ContentParams.id(entry, "id", path, report);
        if (id == null) {
            return;
        }
        String variablePath = path + "<" + id + ">";
        VariableScope scope = scope(entry, variablePath, report);
        if (scope == null) {
            return;
        }
        List<String> enumValues = new ArrayList<>();
        entry.path("values").forEach(value -> enumValues.add(value.asText()));
        TypeSpec type;
        try {
            type = TypeSpec.parse(entry.path("type").asText(""), enumValues);
        } catch (IllegalArgumentException invalid) {
            report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, variablePath, invalid.getMessage());
            return;
        }
        Coercion defaultValue = null;
        if (entry.hasNonNull("default")) {
            defaultValue = ValueCodec.coerce(type, entry.get("default"));
            if (!defaultValue.accepted()) {
                report.error(DiagnosticCode.INVALID_VARIABLE_TYPE, variablePath, "default: " + defaultValue.problem());
                return;
            }
        }
        VariableSchema schema = new VariableSchema(id, type, scope, defaultValue == null ? null : defaultValue.value(),
                entry.path("description").asText(""));
        if (into.putIfAbsent(id, schema) != null) {
            report.error(DiagnosticCode.DUPLICATE_ID, variablePath, "variable " + id + " is declared twice");
        }
    }

    private void tag(JsonNode entry, String path, DiagnosticReport report, Map<NamespacedId, TagSchema> into) {
        NamespacedId id = ContentParams.id(entry, "id", path, report);
        if (id == null) {
            return;
        }
        String tagPath = path + "<" + id + ">";
        VariableScope scope = scope(entry, tagPath, report);
        if (scope == null) {
            return;
        }
        Duration ttl = null;
        if (entry.hasNonNull("ttl")) {
            ttl = ValueCodec.parseDuration(entry.get("ttl").asText());
            if (ttl == null || ttl.isZero() || ttl.isNegative()) {
                report.error(DiagnosticCode.INVALID_PARAMETER, tagPath, "\"ttl\" must be a positive duration such as '10m'");
                return;
            }
        }
        if (into.putIfAbsent(id, new TagSchema(id, scope, ttl, entry.path("description").asText(""))) != null) {
            report.error(DiagnosticCode.DUPLICATE_ID, tagPath, "tag " + id + " is declared twice");
        }
    }

    /** A declaration's scope; player when omitted. Null, and reported, when unknown or unsupported. */
    private VariableScope scope(JsonNode entry, String path, DiagnosticReport report) {
        String raw = entry.path("scope").asText("player");
        VariableScope scope = VariableScope.parse(raw);
        if (scope == null) {
            report.error(DiagnosticCode.INVALID_SCOPE, path, "unknown scope '" + raw + "'");
            return null;
        }
        ScopeSupport.Status status = scopes.status(scope);
        if (status.level() == ScopeSupport.Level.UNSUPPORTED) {
            report.error(DiagnosticCode.UNSUPPORTED_SCOPE, path, scope.id() + " scope is unsupported: " + status.reason());
            return null;
        }
        if (status.level() == ScopeSupport.Level.DEGRADED) {
            report.warning(DiagnosticCode.MISSING_INTEGRATION, path, scope.id() + " scope: " + status.reason());
        }
        return scope;
    }

    /**
     * Every puzzle, overlay, media and cutscene action must name something that exists in this
     * release, wherever it appears: puzzle outputs and handlers, state machines, cutscene steps.
     */
    private static void checkReferences(Map<NamespacedId, PuzzleDefinition> puzzles,
                                        Map<NamespacedId, OverlayDefinition> overlays,
                                        Map<NamespacedId, MediaAsset> media,
                                        Map<NamespacedId, CutsceneDefinition> cutscenes, DiagnosticReport report) {
        List<ActionDefinition> all = new ArrayList<>();
        for (PuzzleDefinition puzzle : puzzles.values()) {
            all.addAll(puzzle.outputs());
            all.addAll(puzzle.onInput());
            all.addAll(puzzle.onMistake());
            all.addAll(puzzle.onReset());
            if (puzzle.rule().machine() != null) {
                puzzle.rule().machine().states().values().forEach(state -> all.addAll(state.onEnter()));
            }
        }
        for (CutsceneDefinition cutscene : cutscenes.values()) {
            cutscene.steps().forEach(step -> all.add(step.action()));
            all.addAll(cutscene.onEnd());
        }
        for (ActionDefinition action : all) {
            if (action.type().equals(MediaActions.PLAY) || action.type().equals(MediaActions.MUSIC_SET)) {
                MediaActions.checkReference(action.type(), action.parameters(), action.path(), media, report);
                continue;
            }
            if (action.type().equals(CutsceneActions.PLAY)) {
                NamespacedId target = NamespacedId.tryParse(action.parameters().path("cutscene").asText()).orElse(null);
                if (target == null || !cutscenes.containsKey(target)) {
                    report.error(DiagnosticCode.MISSING_REFERENCE, action.path(), "names unknown cutscene '"
                            + action.parameters().path("cutscene").asText() + "'");
                }
                continue;
            }
            if (action.type().namespace().equals("mysticquests") && action.type().path().startsWith("overlay.")) {
                NamespacedId overlay = NamespacedId.tryParse(action.parameters().path("overlay").asText()).orElse(null);
                if (overlay == null || !overlays.containsKey(overlay)) {
                    report.error(DiagnosticCode.MISSING_REFERENCE, action.path(), "names unknown overlay '"
                            + action.parameters().path("overlay").asText() + "'");
                }
                continue;
            }
            if (!action.type().equals(PuzzleActions.INPUT) && !action.type().equals(PuzzleActions.RESET)) {
                continue;
            }
            NamespacedId target = NamespacedId.tryParse(action.parameters().path("puzzle").asText()).orElse(null);
            PuzzleDefinition referenced = target == null ? null : puzzles.get(target);
            if (referenced == null) {
                report.error(DiagnosticCode.MISSING_REFERENCE, action.path(), "names unknown puzzle '"
                        + action.parameters().path("puzzle").asText() + "'");
            } else if (action.type().equals(PuzzleActions.INPUT)
                    && referenced.input(action.parameters().path("input").asText()).isEmpty()) {
                report.error(DiagnosticCode.MISSING_REFERENCE, action.path(), "puzzle " + target + " has no input '"
                        + action.parameters().path("input").asText() + "'");
            }
        }
    }

    @FunctionalInterface
    private interface EntryConsumer {
        void accept(JsonNode entry, String path);
    }

    /** Accepts an array of entries, or an object of entries keyed by id (the id is filled in). */
    private static void forEachEntry(JsonNode section, String path, DiagnosticReport report, EntryConsumer consumer) {
        if (section == null || section.isNull()) {
            return;
        }
        if (section.isArray()) {
            for (int index = 0; index < section.size(); index++) {
                consumer.accept(section.get(index), path + "[" + index + "]");
            }
        } else if (section.isObject()) {
            section.properties().forEach(entry -> {
                JsonNode value = entry.getValue();
                if (value.isObject() && !value.has("id")) {
                    value = ((ObjectNode) value).deepCopy().put("id", entry.getKey());
                }
                consumer.accept(value, path + "." + entry.getKey());
            });
        } else {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "section must be an array or an object keyed by id");
        }
    }
}
