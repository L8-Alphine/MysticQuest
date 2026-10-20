package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.model.TypedConfig;

import com.fasterxml.jackson.databind.JsonNode;

import java.util.ArrayList;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.function.Predicate;
import java.util.function.ToIntFunction;

/**
 * Objectives fed by Hytale's own story systems — the goblin breaches, the temple's game flags, boss
 * encounters and instances — and how a signal from them matches one.
 *
 * <table>
 *   <caption>Objective types and the signal each listens for</caption>
 *   <tr><th>Objective</th><th>Signal</th><th>Target</th></tr>
 *   <tr><td>{@code worldEvent}, {@code phase: detected}</td><td>{@code worldEventDetected}</td><td>world-event asset</td></tr>
 *   <tr><td>{@code worldEvent}, {@code phase: completed}</td><td>{@code worldEventCompleted}</td><td>world-event asset</td></tr>
 *   <tr><td>{@code worldEventSignal}</td><td>{@code worldEventSignal}</td><td>signal id</td></tr>
 *   <tr><td>{@code encounter}</td><td>{@code encounterJoined}</td><td>encounter id</td></tr>
 *   <tr><td>{@code bossDefeated}</td><td>{@code bossDefeated}</td><td>NPC role</td></tr>
 *   <tr><td>{@code enterInstance}</td><td>{@code enterInstance}</td><td>instance asset, or world name</td></tr>
 *   <tr><td>{@code gameFlag}</td><td>none: polled</td><td>flag name</td></tr>
 * </table>
 *
 * <p>Each takes its target as {@code target}, as a {@code targets} list (any one counts), or under its
 * own name ({@code event}, {@code signal}, {@code encounter}, {@code boss}, {@code instance},
 * {@code flag}), which may also hold a list. {@code "*"} matches anything.
 *
 * <p>The same definitions serve as a quest's {@code startOn} and {@code unlockOn} triggers.
 */
public final class StoryObjectives {
    public static final String WORLD_EVENT = "worldEvent";
    public static final String WORLD_EVENT_SIGNAL = "worldEventSignal";
    public static final String ENCOUNTER = "encounter";
    public static final String BOSS_DEFEATED = "bossDefeated";
    public static final String ENTER_INSTANCE = "enterInstance";
    public static final String GAME_FLAG = "gameFlag";

    public static final String DETECTED_SIGNAL = "worldEventDetected";
    public static final String COMPLETED_SIGNAL = "worldEventCompleted";
    public static final String ENCOUNTER_SIGNAL = "encounterJoined";

    public static final Set<String> TYPES = Set.of(
            WORLD_EVENT, WORLD_EVENT_SIGNAL, ENCOUNTER, BOSS_DEFEATED, ENTER_INSTANCE, GAME_FLAG);

    private StoryObjectives() {
    }

    public static boolean handles(String type) {
        return type != null && TYPES.contains(type);
    }

    /** Whether {@code signal} counts toward {@code objective}. Game flags are polled, never signalled. */
    public static boolean matches(ObjectiveDefinition objective, QuestSignal signal) {
        return switch (objective.type()) {
            case WORLD_EVENT -> signalFor(objective).equals(signal.type())
                    && named(objective, signal.target(), "event");
            case WORLD_EVENT_SIGNAL -> WORLD_EVENT_SIGNAL.equals(signal.type())
                    && named(objective, signal.target(), "signal");
            case ENCOUNTER -> ENCOUNTER_SIGNAL.equals(signal.type())
                    && named(objective, signal.target(), "encounter");
            case BOSS_DEFEATED -> BOSS_DEFEATED.equals(signal.type())
                    && named(objective, signal.target(), "boss", "role");
            // An instance answers to its asset name and to the world it was copied into.
            case ENTER_INSTANCE -> ENTER_INSTANCE.equals(signal.type())
                    && (named(objective, signal.target(), "instance", "world")
                    || (signal.targetContext() != null && named(objective, signal.targetContext().worldId(), "instance", "world")));
            default -> false;
        };
    }

    /** The signal a {@code worldEvent} objective waits for, from its {@code phase}. */
    public static String signalFor(TypedConfig objective) {
        return switch (phase(objective)) {
            case "completed" -> COMPLETED_SIGNAL;
            case "detected" -> DETECTED_SIGNAL;
            default -> "";
        };
    }

    /**
     * The phase a {@code worldEvent} objective names, normalised; {@code detected} when it names none.
     * Unknown words come back as they were, which the loader reports.
     */
    public static String phase(TypedConfig objective) {
        String phase = objective.text("phase", "detected").strip().toLowerCase(Locale.ROOT);
        return switch (phase) {
            case "detected", "detect", "seen", "found", "spotted" -> "detected";
            case "completed", "complete", "cleared", "finished", "ended" -> "completed";
            default -> phase;
        };
    }

    /** Whether a {@code gameFlag} definition's flag stands where it asks: {@code level} (1) by {@code comparator} ({@code >=}). */
    public static boolean flagPasses(TypedConfig definition, ToIntFunction<String> levels) {
        List<String> flags = targets(definition, "flag");
        if (flags.isEmpty()) {
            return false;
        }
        int wanted = definition.integer("level", definition.integer("value", 1));
        String comparator = definition.text("comparator", definition.text("operator", ">="));
        for (String flag : flags) {
            if (!compare(levels.applyAsInt(flag), comparator, wanted)) {
                return false;
            }
        }
        return true;
    }

    /** Every target a definition names: {@code targets}, else {@code target}, else its own key. */
    public static List<String> targets(TypedConfig definition, String... ownKeys) {
        List<String> found = new ArrayList<>();
        collect(definition.data().get("targets"), found);
        collect(definition.data().get("target"), found);
        for (String key : ownKeys) {
            collect(definition.data().get(key), found);
        }
        return found;
    }

    /**
     * Which NPC roles any loaded quest waits on with {@code bossDefeated}, in objectives or triggers.
     * Boss deaths are only fanned out to nearby players for these, so ordinary kills cost nothing.
     */
    public static Predicate<String> watchedBosses(LoadedContent content) {
        Set<String> roles = new LinkedHashSet<>();
        for (QuestDefinition quest : content.quests().values()) {
            addBosses(quest.objectives(), roles);
            addBosses(quest.startOn(), roles);
            addBosses(quest.unlockOn(), roles);
        }
        addBosses(content.objectives().values(), roles);
        if (roles.contains("*")) {
            return role -> true;
        }
        Set<String> frozen = Set.copyOf(roles);
        return frozen::contains;
    }

    /** Whether a quest waits on a polled game flag anywhere: objectives or triggers. */
    public static boolean watchesFlags(QuestDefinition quest) {
        return hasType(quest.objectives()) || hasType(quest.startOn()) || hasType(quest.unlockOn());
    }

    private static boolean hasType(Iterable<ObjectiveDefinition> definitions) {
        for (ObjectiveDefinition definition : definitions) {
            if (GAME_FLAG.equals(definition.type())) {
                return true;
            }
        }
        return false;
    }

    private static void addBosses(Iterable<ObjectiveDefinition> definitions, Set<String> roles) {
        for (ObjectiveDefinition definition : definitions) {
            if (BOSS_DEFEATED.equals(definition.type())) {
                roles.addAll(targets(definition, "boss", "role"));
            }
        }
    }

    private static boolean named(TypedConfig definition, String actual, String... ownKeys) {
        if (actual == null || actual.isEmpty()) {
            return false;
        }
        for (String target : targets(definition, ownKeys)) {
            if (target.equals("*") || target.equals(actual)) {
                return true;
            }
        }
        return false;
    }

    private static void collect(JsonNode node, List<String> into) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(element -> collect(element, into));
            return;
        }
        String text = node.asText("").strip();
        if (!text.isEmpty()) {
            into.add(text);
        }
    }

    private static boolean compare(int actual, String comparator, int expected) {
        return switch (comparator.strip()) {
            case "=", "==", "equals", "Equal" -> actual == expected;
            case "!=", "<>" -> actual != expected;
            case ">", "GreaterThan" -> actual > expected;
            case "<", "LessThan" -> actual < expected;
            case "<=", "LessOrEqual" -> actual <= expected;
            default -> actual >= expected;
        };
    }
}
