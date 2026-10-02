package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.StageDefinition;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.function.Function;

/**
 * One step of a quest as a UI surface sees it: a named group of objectives, its position in the
 * quest, and the objectives themselves.
 *
 * <p>Steps are presentation only — {@link org.hyzionstudios.mysticquests.model.StageDefinition}
 * explains why. The HUD shows the current step's objectives instead of the quest's first four, which
 * is the whole point of grouping: a thirteen-objective quest reads as "step 2 of 4" rather than as a
 * list that runs off the panel.
 *
 * @param stageId the authored stage id, empty for objectives the author never grouped
 * @param index 1-based position among the quest's steps
 * @param total how many steps the quest has
 */
public record StageView(
        String stageId,
        String displayName,
        int index,
        int total,
        List<ObjectiveView> objectives) {

    /** Display name for the leading group when some objectives are grouped and others are not. */
    private static final String UNNAMED_GROUP = "Objectives";

    public boolean complete() {
        return objectives.stream().allMatch(ObjectiveView::complete);
    }

    public int completedObjectiveCount() {
        return (int) objectives.stream().filter(ObjectiveView::complete).count();
    }

    /** Whether this step carries an authored name, as opposed to holding ungrouped objectives. */
    public boolean named() {
        return !stageId.isEmpty();
    }

    /** {@code "STEP 2 OF 4"} for the HUD's step line. */
    public String stepLabel() {
        return "STEP " + index + " OF " + total;
    }

    /** {@code "1 / 3"} — objectives done within this step. */
    public String progressLabel() {
        return completedObjectiveCount() + " / " + objectives.size();
    }

    /** The single synthetic step used by surfaces that show a one-line note instead of progress. */
    public static StageView single(List<ObjectiveView> objectives) {
        return new StageView("", "", 1, 1, List.copyOf(objectives));
    }

    /**
     * Groups a quest's objectives into ordered steps.
     *
     * <p>The rules, in the order they apply:
     *
     * <ul>
     *   <li>An objective with no {@code stage} joins the step of the objective above it, the way a
     *       document's paragraphs fall under the last heading. Objectives before any stage is named
     *       form one leading unnamed group.
     *   <li>When the quest declares {@code stages}, those set the order. Stage ids used by an
     *       objective but never declared still work; they follow, in the order they first appear.
     *   <li>A stage with no declared display name gets one derived from its id, so
     *       {@code "citadel_keepers"} shows as "Citadel Keepers".
     * </ul>
     *
     * <p>A quest whose objectives name no stage at all yields exactly one unnamed step holding all
     * of them, which is what every quest written before steps existed does.
     */
    public static List<StageView> group(
            List<StageDefinition> declared,
            List<ObjectiveDefinition> objectives,
            Function<ObjectiveDefinition, ObjectiveView> viewer) {
        Map<String, List<ObjectiveView>> grouped = new LinkedHashMap<>();
        String inherited = "";
        for (ObjectiveDefinition objective : objectives) {
            String stageId = objective.stage();
            if (!stageId.isEmpty()) {
                inherited = stageId;
            }
            grouped.computeIfAbsent(inherited, key -> new ArrayList<>()).add(viewer.apply(objective));
        }
        if (grouped.isEmpty()) {
            return List.of();
        }

        List<String> order = new ArrayList<>();
        for (StageDefinition stage : declared) {
            String stageId = stage.id() == null ? "" : stage.id().trim();
            if (grouped.containsKey(stageId) && !order.contains(stageId)) {
                order.add(stageId);
            }
        }
        for (String stageId : grouped.keySet()) {
            if (!order.contains(stageId)) {
                order.add(stageId);
            }
        }

        List<StageView> stages = new ArrayList<>(order.size());
        for (int index = 0; index < order.size(); index++) {
            String stageId = order.get(index);
            stages.add(new StageView(
                    stageId,
                    displayNameFor(stageId, declared, order.size()),
                    index + 1,
                    order.size(),
                    List.copyOf(grouped.get(stageId))));
        }
        return List.copyOf(stages);
    }

    private static String displayNameFor(String stageId, List<StageDefinition> declared, int stageCount) {
        if (stageId.isEmpty()) {
            // A quest with one unnamed step is simply ungrouped, and surfaces hide the step line
            // entirely; a leading unnamed group beside named ones still needs something to show.
            return stageCount > 1 ? UNNAMED_GROUP : "";
        }
        for (StageDefinition stage : declared) {
            if (stageId.equals(stage.id() == null ? "" : stage.id().trim())) {
                return stage.displayName();
            }
        }
        return humanize(stageId);
    }

    /** {@code "citadel_keepers"} → {@code "Citadel Keepers"}, for stages nobody declared. */
    private static String humanize(String stageId) {
        String local = stageId.substring(stageId.lastIndexOf(':') + 1);
        StringBuilder name = new StringBuilder(local.length());
        boolean startOfWord = true;
        for (char character : local.toCharArray()) {
            if (character == '_' || character == '-' || character == '.') {
                name.append(' ');
                startOfWord = true;
                continue;
            }
            name.append(startOfWord ? Character.toUpperCase(character) : character);
            startOfWord = false;
        }
        return name.toString().trim().isEmpty() ? local.toUpperCase(Locale.ROOT) : name.toString().trim();
    }
}
