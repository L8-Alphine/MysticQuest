package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Every quest package written before the canonical schema must keep working, so these pin down the
 * alias rewrites rather than trusting that content still loads.
 */
final class ContentSchemaTest {
    private final ObjectMapper mapper = Json.createMapper();

    @Test
    void eventAliasesCarryTheirOperationAndScope() throws IOException {
        EventDefinition addTag = event("{\"type\":\"addTag\",\"tag\":\"met_elder\"}");
        assertEquals("tag", addTag.type());
        assertEquals("add", addTag.text("op", ""));

        EventDefinition removeTag = event("{\"type\":\"removeTag\",\"tag\":\"met_elder\"}");
        assertEquals("tag", removeTag.type());
        assertEquals("remove", removeTag.text("op", ""));

        EventDefinition globalTag = event("{\"type\":\"globalTag\",\"tag\":\"festival\"}");
        assertEquals("tag", globalTag.type());
        assertEquals("add", globalTag.text("op", ""));
        assertEquals("global", globalTag.text("scope", ""));

        EventDefinition increment = event("{\"type\":\"incrementVariable\",\"key\":\"kills\",\"amount\":2}");
        assertEquals("variable", increment.type());
        assertEquals("increment", increment.text("op", ""));

        EventDefinition entityVariable = event("{\"type\":\"entityVariable\",\"key\":\"spoken\",\"value\":\"true\"}");
        assertEquals("variable", entityVariable.type());
        assertEquals("set", entityVariable.text("op", ""));
        assertEquals("entity", entityVariable.text("scope", ""));
    }

    @Test
    void conditionAliasesCarryScopeAndInversion() throws IOException {
        ConditionDefinition notTag = condition("{\"type\":\"notTag\",\"tag\":\"met_elder\"}");
        assertEquals("tag", notTag.type());
        assertTrue(notTag.bool("invert", false));

        ConditionDefinition plainTag = condition("{\"type\":\"tag\",\"tag\":\"met_elder\"}");
        assertEquals("tag", plainTag.type());
        assertFalse(plainTag.bool("invert", false));

        ConditionDefinition volumeTag = condition("{\"type\":\"volumeTag\",\"tag\":\"visited\"}");
        assertEquals("tag", volumeTag.type());
        assertEquals("volume", volumeTag.text("scope", ""));
        assertFalse(volumeTag.bool("invert", false));
    }

    /** The same alias means different things on each side, so kind must not be inherited blindly. */
    @Test
    void kindIsDecidedPerFieldNotInherited() throws IOException {
        ObjectNode tree = (ObjectNode) mapper.readTree("""
                {
                  "type": "if",
                  "conditions": [ { "type": "notTag", "tag": "done" } ],
                  "then": [ { "type": "addTag", "tag": "done" } ],
                  "else": [ { "type": "globalVariable", "key": "misses", "value": "1" } ]
                }
                """);

        ContentSchema.canonicalizeTree(tree, ContentSchema.Kind.EVENT);

        ObjectNode gate = (ObjectNode) tree.get("conditions").get(0);
        assertEquals("tag", gate.get("type").asText());
        assertTrue(gate.get("invert").asBoolean(), "a nested condition must be read as a condition");
        assertFalse(gate.has("op"), "conditions have no operation");

        ObjectNode thenBranch = (ObjectNode) tree.get("then").get(0);
        assertEquals("tag", thenBranch.get("type").asText());
        assertEquals("add", thenBranch.get("op").asText());

        ObjectNode elseBranch = (ObjectNode) tree.get("else").get(0);
        assertEquals("variable", elseBranch.get("type").asText());
        assertEquals("global", elseBranch.get("scope").asText());
    }

    /** An author who already wrote canonical form must not have it rewritten underneath them. */
    @Test
    void canonicalContentIsLeftAlone() throws IOException {
        EventDefinition canonical =
                event("{\"type\":\"variable\",\"op\":\"increment\",\"scope\":\"entity\",\"key\":\"kills\"}");
        assertEquals("variable", canonical.type());
        assertEquals("increment", canonical.text("op", ""));
        assertEquals("entity", canonical.text("scope", ""));
    }

    /** An explicit op on a legacy alias wins, so a hand-tuned definition is not clobbered. */
    @Test
    void explicitOperationSurvivesAliasRewrite() throws IOException {
        EventDefinition mixed = event("{\"type\":\"entityTag\",\"op\":\"remove\",\"tag\":\"busy\"}");
        assertEquals("tag", mixed.type());
        assertEquals("remove", mixed.text("op", ""));
        assertEquals("entity", mixed.text("scope", ""));
    }

    private EventDefinition event(String json) throws IOException {
        EventDefinition definition = mapper.readValue(json, EventDefinition.class);
        ContentSchema.canonicalize(definition, ContentSchema.Kind.EVENT);
        return definition;
    }

    private ConditionDefinition condition(String json) throws IOException {
        ConditionDefinition definition = mapper.readValue(json, ConditionDefinition.class);
        ContentSchema.canonicalize(definition, ContentSchema.Kind.CONDITION);
        return definition;
    }
}
