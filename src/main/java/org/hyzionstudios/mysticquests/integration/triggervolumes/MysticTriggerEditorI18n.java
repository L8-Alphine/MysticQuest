package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.modules.i18n.I18nModule;
import com.hypixel.hytale.server.core.modules.i18n.parser.LangFileParser;

import java.io.BufferedReader;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.lang.reflect.Field;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/** Loads the labels and tooltips used by Hytale's trigger-volume editor. */
public final class MysticTriggerEditorI18n {
    static final String RESOURCE = "Server.Languages.en-US/server.lang";
    private static final String PREFIX = "server.";
    private static final String DEFAULT_LANGUAGE = "en-US";

    private MysticTriggerEditorI18n() {
    }

    /**
     * Merges this mod's editor strings into Hytale's live language maps.
     *
     * <p>The language file is also packaged as an asset. The explicit merge is necessary because
     * the trigger-volume editor may cache missing keys before a mod asset pack is available. This
     * follows the same startup path used by HyExtras and degrades to a warning if Hytale changes its
     * internal i18n fields.
     */
    @SuppressWarnings("unchecked")
    public static void load(HytaleLogger logger) {
        try (InputStream stream = MysticTriggerEditorI18n.class.getClassLoader().getResourceAsStream(RESOURCE)) {
            if (stream == null) {
                logger.at(Level.WARNING).log("[MysticQuests i18n] Missing language resource: " + RESOURCE);
                return;
            }

            Map<String, String> parsed;
            try (BufferedReader reader = new BufferedReader(new InputStreamReader(stream, StandardCharsets.UTF_8))) {
                parsed = LangFileParser.parse(RESOURCE, reader);
            }

            I18nModule i18n = I18nModule.get();
            if (i18n == null) {
                logger.at(Level.WARNING)
                        .log("[MysticQuests i18n] I18nModule unavailable; trigger editor labels may show raw keys.");
                return;
            }

            Field bundledDefaultsField = I18nModule.class.getDeclaredField("bundledDefaults");
            bundledDefaultsField.setAccessible(true);
            Map<String, String> bundledDefaults = (Map<String, String>) bundledDefaultsField.get(i18n);

            Field languagesField = I18nModule.class.getDeclaredField("languages");
            languagesField.setAccessible(true);
            Map<String, Map<String, String>> languages =
                    (Map<String, Map<String, String>>) languagesField.get(i18n);
            Map<String, String> defaultLanguage =
                    languages.computeIfAbsent(DEFAULT_LANGUAGE, ignored -> new ConcurrentHashMap<>());

            int loaded = 0;
            for (Map.Entry<String, String> entry : parsed.entrySet()) {
                String key = entry.getKey().startsWith(PREFIX) ? entry.getKey() : PREFIX + entry.getKey();
                bundledDefaults.put(key, entry.getValue());
                defaultLanguage.put(key, entry.getValue());
                loaded++;
            }

            Field cachedLanguagesField = I18nModule.class.getDeclaredField("cachedLanguages");
            cachedLanguagesField.setAccessible(true);
            ((Map<?, ?>) cachedLanguagesField.get(i18n)).clear();

            logger.at(Level.INFO).log("[MysticQuests i18n] Loaded " + loaded
                    + " trigger editor translations from " + RESOURCE + ".");
        } catch (Exception exception) {
            logger.at(Level.WARNING).withCause(exception)
                    .log("[MysticQuests i18n] Could not merge trigger editor translations; "
                            + "Hytale i18n internals may have changed.");
        }
    }
}
