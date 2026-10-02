package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.TypedConfig;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.BooleanNode;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.databind.node.TextNode;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;

/**
 * Normalises authored content onto the canonical tag and variable schema at load time.
 *
 * <p>State used to be addressed through eleven type aliases — {@code addTag}, {@code globalTag},
 * {@code entityVariable}, and so on — each of which encoded scope and operation in its name. That
 * meant every consumer (the quest dispatcher, the state service, the trigger bridge) had to know the
 * whole table, and adding a scope meant touching all of them. Canonically there are two state types:
 *
 * <pre>
 *   { "type": "tag",      "op": "add|remove", "scope": "...", "target": "...", "tag": "..." }
 *   { "type": "variable", "op": "set|remove|increment", "scope": "...", "key": "...", "value": "..." }
 * </pre>
 *
 * <p>with conditions using the same two types and no {@code op}. Rewriting happens once here, so
 * every quest package written against the old aliases keeps working unchanged and nothing pays a
 * translation cost at runtime.
 */
public final class ContentSchema {
    /** Canonical type for tag reads and writes. */
    public static final String TYPE_TAG = "tag";
    /** Canonical type for variable reads and writes. */
    public static final String TYPE_VARIABLE = "variable";

    /** Fields whose contents are events, for recursion. */
    private static final Set<String> EVENT_FIELDS =
            Set.of("events", "then", "else", "startEvents", "completeEvents", "rewards");
    /** Fields whose contents are conditions, for recursion. */
    private static final Set<String> CONDITION_FIELDS =
            Set.of("conditions", "condition", "startConditions", "reacceptConditions");

    private static final Map<String, Rewrite> EVENT_ALIASES = Map.ofEntries(
            Map.entry("addTag", new Rewrite(TYPE_TAG, "add", null, null)),
            Map.entry("removeTag", new Rewrite(TYPE_TAG, "remove", null, null)),
            Map.entry("globalTag", new Rewrite(TYPE_TAG, "add", "global", null)),
            Map.entry("entityTag", new Rewrite(TYPE_TAG, "add", "entity", null)),
            Map.entry("blockTag", new Rewrite(TYPE_TAG, "add", "block", null)),
            Map.entry("volumeTag", new Rewrite(TYPE_TAG, "add", "volume", null)),
            Map.entry("setVariable", new Rewrite(TYPE_VARIABLE, "set", null, null)),
            Map.entry("removeVariable", new Rewrite(TYPE_VARIABLE, "remove", null, null)),
            Map.entry("incrementVariable", new Rewrite(TYPE_VARIABLE, "increment", null, null)),
            Map.entry("globalVariable", new Rewrite(TYPE_VARIABLE, "set", "global", null)),
            Map.entry("entityVariable", new Rewrite(TYPE_VARIABLE, "set", "entity", null)),
            Map.entry("blockVariable", new Rewrite(TYPE_VARIABLE, "set", "block", null)),
            Map.entry("volumeVariable", new Rewrite(TYPE_VARIABLE, "set", "volume", null)));

    private static final Map<String, Rewrite> CONDITION_ALIASES = Map.ofEntries(
            Map.entry("notTag", new Rewrite(TYPE_TAG, null, null, true)),
            Map.entry("globalTag", new Rewrite(TYPE_TAG, null, "global", null)),
            Map.entry("entityTag", new Rewrite(TYPE_TAG, null, "entity", null)),
            Map.entry("blockTag", new Rewrite(TYPE_TAG, null, "block", null)),
            Map.entry("volumeTag", new Rewrite(TYPE_TAG, null, "volume", null)),
            Map.entry("globalVariable", new Rewrite(TYPE_VARIABLE, null, "global", null)),
            Map.entry("entityVariable", new Rewrite(TYPE_VARIABLE, null, "entity", null)),
            Map.entry("blockVariable", new Rewrite(TYPE_VARIABLE, null, "block", null)),
            Map.entry("volumeVariable", new Rewrite(TYPE_VARIABLE, null, "volume", null)));

    /** Which half of the schema a node belongs to; the same alias can mean different things. */
    public enum Kind { EVENT, CONDITION }

    private ContentSchema() {
    }

    /**
     * Rewrites a standalone definition and everything nested inside it.
     *
     * <p>Covers both authoring paths: definitions parsed from JSON and definitions produced by the
     * BetonQuest-style script parser, which also emits the legacy type names.
     */
    public static void canonicalize(TypedConfig definition, Kind kind) {
        if (definition == null) {
            return;
        }
        Rewrite rewrite = aliases(kind).get(definition.type());
        if (rewrite != null) {
            definition.setType(rewrite.type());
            if (rewrite.op() != null && !definition.data().containsKey("op")) {
                definition.put("op", TextNode.valueOf(rewrite.op()));
            }
            if (rewrite.scope() != null) {
                definition.put("scope", TextNode.valueOf(rewrite.scope()));
            }
            if (rewrite.invert() != null && !definition.data().containsKey("invert")) {
                definition.put("invert", BooleanNode.valueOf(rewrite.invert()));
            }
        }
        definition.data().forEach((field, value) -> canonicalizeChild(field, value, kind));
    }

    /**
     * Rewrites a raw content tree in place before it is bound to model objects. Used for quests and
     * conversations, whose event and condition lists are converted wholesale by Jackson.
     */
    public static void canonicalizeTree(@Nullable JsonNode node, Kind kind) {
        if (node == null || node.isNull()) {
            return;
        }
        if (node.isArray()) {
            node.forEach(element -> canonicalizeTree(element, kind));
            return;
        }
        if (!node.isObject()) {
            return;
        }
        ObjectNode object = (ObjectNode) node;
        JsonNode typeNode = object.get("type");
        if (typeNode != null && typeNode.isTextual()) {
            Rewrite rewrite = aliases(kind).get(typeNode.asText());
            if (rewrite != null) {
                object.put("type", rewrite.type());
                if (rewrite.op() != null && !object.has("op")) {
                    object.put("op", rewrite.op());
                }
                if (rewrite.scope() != null) {
                    object.put("scope", rewrite.scope());
                }
                if (rewrite.invert() != null && !object.has("invert")) {
                    object.put("invert", rewrite.invert());
                }
            }
        }
        object.properties().forEach(field -> canonicalizeChild(field.getKey(), field.getValue(), kind));
    }

    /**
     * Recurses into a field, switching kind when the field name says so. A {@code then} branch holds
     * events even when it sits inside a condition, and {@code conditions} holds conditions even
     * inside an event, so kind is decided per field rather than inherited.
     */
    private static void canonicalizeChild(String field, JsonNode value, Kind inherited) {
        if (EVENT_FIELDS.contains(field)) {
            canonicalizeTree(value, Kind.EVENT);
        } else if (CONDITION_FIELDS.contains(field)) {
            canonicalizeTree(value, Kind.CONDITION);
        } else if (value != null && (value.isArray() || value.isObject())) {
            canonicalizeTree(value, inherited);
        }
    }

    private static Map<String, Rewrite> aliases(Kind kind) {
        return kind == Kind.EVENT ? EVENT_ALIASES : CONDITION_ALIASES;
    }

    /**
     * @param op the operation to stamp when the alias implies one; null leaves it to the author
     * @param scope overrides any authored scope, because the alias names the scope explicitly
     * @param invert set for {@code notTag}, whose whole meaning is the negation
     */
    private record Rewrite(String type, @Nullable String op, @Nullable String scope, @Nullable Boolean invert) {
    }
}
