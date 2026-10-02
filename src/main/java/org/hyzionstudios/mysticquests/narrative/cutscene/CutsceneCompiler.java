package org.hyzionstudios.mysticquests.narrative.cutscene;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionCompiler;
import org.hyzionstudios.mysticquests.narrative.action.ActionDefinition;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition.AudienceMode;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;

/**
 * Reads the {@code cutscenes} section and enforces the skip contract (§17.1) at load time: state
 * changes cannot be cosmetic, because a skip drops cosmetic steps, and a scene that moves the
 * camera must put it back in {@code onEnd}, because a skipped or interrupted scene never reaches
 * its last step.
 */
public final class CutsceneCompiler {
    /** Built-in actions that change story state; a skip must never drop them. */
    private static final Set<String> STATE_ACTIONS = Set.of(
            "tag.", "variable.", "trigger.", "puzzle.", "overlay.", "music.", "entity.", "cutscene.", "signal");

    private CutsceneCompiler() {
    }

    @Nullable
    public static CutsceneDefinition compile(JsonNode node, String path, CompileContext context, DiagnosticReport report) {
        NamespacedId id = ContentParams.id(node, "id", path, report);
        if (id == null) {
            return null;
        }
        String scenePath = path + "<" + id + ">";
        int errors = report.errors().size();
        String story = node.path("story").asText(id.toString()).trim();
        if (story.isEmpty()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, scenePath, "\"story\" must not be blank");
        }
        AudienceMode audience = AudienceMode.AUTO;
        if (node.hasNonNull("audience")) {
            try {
                audience = AudienceMode.valueOf(node.get("audience").asText().trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                report.error(DiagnosticCode.INVALID_PARAMETER, scenePath, "unknown audience '" + node.get("audience").asText() + "'",
                        "use player, party or auto");
            }
        }

        List<CutsceneDefinition.Step> steps = new ArrayList<>();
        JsonNode rawSteps = node.get("steps");
        if (rawSteps == null || !rawSteps.isArray() || rawSteps.isEmpty()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, scenePath, "a cutscene needs a non-empty \"steps\" array");
        } else {
            Set<String> keys = new HashSet<>();
            for (int index = 0; index < rawSteps.size(); index++) {
                CutsceneDefinition.Step step = step(rawSteps.get(index), index, scenePath + ".steps[" + index + "]", context, report);
                if (step != null && !keys.add(step.key())) {
                    report.error(DiagnosticCode.DUPLICATE_ID, scenePath + ".steps[" + index + "]", "stepId '" + step.key() + "' is used twice");
                } else if (step != null) {
                    steps.add(step);
                }
            }
        }
        steps.sort(Comparator.comparingLong(CutsceneDefinition.Step::atMillis));
        List<ActionDefinition> onEnd = ActionCompiler.compile(node.get("onEnd"), scenePath + ".onEnd", context, report);

        boolean movesCamera = steps.stream().anyMatch(step -> isCamera(step.action()));
        if (movesCamera && onEnd.stream().noneMatch(CutsceneCompiler::isCamera)) {
            report.error(DiagnosticCode.CUTSCENE_NO_RECOVERY, scenePath, "changes the camera but never puts it back",
                    "add a setCamera step to \"onEnd\"; it runs however the scene ends, including a skip or a disconnect");
        }
        if (steps.stream().anyMatch(step -> step.action().type().equals(NamespacedId.of("mysticquests", "music.set")))
                && onEnd.stream().noneMatch(action -> action.type().namespace().equals("mysticquests")
                && action.type().path().startsWith("music."))) {
            report.warning(DiagnosticCode.UNTERMINATED_MEDIA, scenePath, "sets music that outlives the scene; add music.clear "
                    + "(or music.set) to \"onEnd\" if the music belongs to the scene only");
        }
        if (report.errors().size() > errors) {
            return null;
        }
        return new CutsceneDefinition(id, context.packageId(), story, audience,
                node.path("skippable").asBoolean(true), steps, onEnd);
    }

    @Nullable
    private static CutsceneDefinition.Step step(JsonNode entry, int index, String path, CompileContext context, DiagnosticReport report) {
        if (entry == null || !entry.isObject()) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "a step must be a JSON object");
            return null;
        }
        ObjectNode action = ((ObjectNode) entry).deepCopy();
        JsonNode at = action.remove("at");
        JsonNode cosmeticNode = action.remove("cosmetic");
        double seconds = at == null ? 0 : at.asDouble(-1);
        if (at != null && (!at.isNumber() || seconds < 0)) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, "\"at\" is a number of seconds from the start, zero or more");
            return null;
        }
        boolean cosmetic = cosmeticNode != null && cosmeticNode.asBoolean(false);
        List<ActionDefinition> compiled = ActionCompiler.compile(action, path, context, report);
        if (compiled.isEmpty()) {
            return null;
        }
        ActionDefinition definition = compiled.getFirst();
        if (cosmetic && changesState(definition)) {
            report.error(DiagnosticCode.INVALID_PARAMETER, path, definition.type() + " changes story state, so it cannot be cosmetic",
                    "a skip drops cosmetic steps; leave this one required so skipped scenes end in the same state");
            return null;
        }
        String key = entry.hasNonNull(ActionCompiler.STEP_ID) ? entry.get(ActionCompiler.STEP_ID).asText().trim() : "s" + index;
        return new CutsceneDefinition.Step(key, Math.round(seconds * 1000), cosmetic, definition);
    }

    private static boolean changesState(ActionDefinition action) {
        if (!action.type().namespace().equals("mysticquests") || action.type().equals(ActionCompiler.LEGACY_EVENT)) {
            return false;
        }
        String path = action.type().path();
        return STATE_ACTIONS.stream().anyMatch(path::startsWith);
    }

    /** A v1 {@code setCamera} event run through the compatibility bridge. */
    private static boolean isCamera(ActionDefinition action) {
        return action.type().equals(ActionCompiler.LEGACY_EVENT)
                && action.parameters().path("event").path("type").asText("").equals("setCamera");
    }
}
