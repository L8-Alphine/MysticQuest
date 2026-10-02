package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.junit.jupiter.api.Test;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.nio.charset.StandardCharsets;
import java.util.HashMap;
import java.util.Map;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;

class MysticTriggerEditorI18nTest {
    @Test
    void packagedTranslationsUseTheExactNamespacedEditorKeys() throws Exception {
        InputStream stream = getClass().getClassLoader().getResourceAsStream(MysticTriggerEditorI18n.RESOURCE);
        assertNotNull(stream);

        Map<String, String> translations;
        try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
            translations = new HashMap<>();
            for (String line; (line = reader.readLine()) != null; ) {
                int separator = line.indexOf(" = ");
                if (separator > 0 && !line.startsWith("#")) {
                    translations.put(line.substring(0, separator), line.substring(separator + 3));
                }
            }
        }

        assertEquals("Run MysticQuests Action",
                translations.get("customUI.triggerVolumeEffectEditor.effectType.mysticquests:action"));
        assertEquals("MysticQuests Variable",
                translations.get("customUI.triggerVolumeEffectEditor.conditionType.mysticquests:variable"));
        assertEquals("Action Type",
                translations.get("customUI.triggerVolumeEffectEditor.field.mysticquests:action.Action"));
        assertEquals("Variable Name",
                translations.get("customUI.triggerVolumeEffectEditor.field.mysticquests:variable.Key"));
        assertEquals("Send Rich Message",
                translations.get("customUI.triggerVolumeEffectEditor.effectType.mysticquests:rich_message"));
        assertEquals("All Online Players",
                translations.get(
                        "customUI.triggerVolumeEffectEditor.field.mysticquests:rich_message.Audience.option.global"));
        assertEquals("Run Command",
                translations.get("customUI.triggerVolumeEffectEditor.effectType.mysticquests:run_command"));
        assertEquals("Server Console",
                translations.get(
                        "customUI.triggerVolumeEffectEditor.field.mysticquests:run_command.ExecuteAs.option.console"));
    }
}
