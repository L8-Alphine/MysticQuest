package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.model.EventDefinition;
import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.Test;

import java.io.IOException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

class ScriptInstructionParserTest {
    private final ScriptInstructionParser parser = new ScriptInstructionParser(Json.createMapper());

    @Test
    void preservesQuotedWhitespaceEscapesAndNamedArguments() throws IOException {
        EventDefinition event = parser.event("notify \"First line\\nSecond line\" category:quest_complete io:title");

        assertEquals("notification", event.type());
        assertEquals("First line\nSecond line", event.text("body", ""));
        assertEquals("quest_complete", event.text("category", ""));
        assertEquals("title", event.text("io", ""));
    }

    @Test
    void convertsBetonStyleObjectiveAliasesAndFlags() throws IOException {
        ObjectiveDefinition objective = parser.objective(
                "bandits", "mobkill Bandit 5 name:\"Defeat the patrol\" persistent notify");

        assertEquals("kill", objective.type());
        assertEquals("Bandit", objective.text("target", ""));
        assertEquals(5, objective.integer("amount", 0));
        assertEquals("Defeat the patrol", objective.displayName());
        assertEquals(2, objective.data().get("flags").size());
    }

    @Test
    void rejectsUnclosedQuotes() {
        IOException error = assertThrows(IOException.class, () -> parser.parse("notify \"broken"));
        assertTrue(error.getMessage().contains("unclosed quote"));
    }
}
