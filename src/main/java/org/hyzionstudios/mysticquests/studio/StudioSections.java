package org.hyzionstudios.mysticquests.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.EnumSet;
import java.util.Iterator;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Set;
import java.util.TreeSet;

/**
 * Which capability an edit needs, from the content sections it changes (§19.2): conversations need
 * {@link StudioCapability#DIALOGUE}, media and cutscenes {@link StudioCapability#AUDIO}, puzzles
 * {@link StudioCapability#PUZZLES}, world overlays {@link StudioCapability#TRIGGERS}, and anything
 * else (quests, rewards, events, schemas, the package manifest) {@link StudioCapability#EDIT}.
 *
 * <p>Sections are compared by value, so a writer can save a file that also holds quests as long as
 * the quests are unchanged.
 */
public final class StudioSections {
    private static final Map<String, StudioCapability> SECTIONS = Map.of(
            "conversations", StudioCapability.DIALOGUE,
            "speakers", StudioCapability.AUDIO,
            "media", StudioCapability.AUDIO,
            "cutscenes", StudioCapability.AUDIO,
            "puzzles", StudioCapability.PUZZLES,
            "overlays", StudioCapability.TRIGGERS);

    private StudioSections() {
    }

    public static StudioCapability capabilityFor(String section) {
        return SECTIONS.getOrDefault(section, StudioCapability.EDIT);
    }

    /** The capabilities needed to change {@code before} into {@code after}; either may be null (create, delete). */
    public static Set<StudioCapability> required(String path, @Nullable String before, @Nullable String after, ObjectMapper parser)
            throws StudioException {
        Set<StudioCapability> needed = EnumSet.noneOf(StudioCapability.class);
        for (String section : changed(path, before, after, parser)) {
            needed.add(capabilityFor(section));
        }
        return needed;
    }

    /** Top-level sections that differ. A manifest is the section {@code package}; an array file is named by its file. */
    public static Set<String> changed(String path, @Nullable String before, @Nullable String after, ObjectMapper parser)
            throws StudioException {
        String name = path.substring(path.lastIndexOf('/') + 1);
        String stem = name.substring(0, name.lastIndexOf('.')).toLowerCase(Locale.ROOT);
        if (stem.equals("package")) {
            return Set.of("package");
        }
        JsonNode old = parse(before, parser);
        JsonNode updated = parse(after, parser);
        Set<String> sections = new TreeSet<>();
        if ((old != null && !old.isObject()) || (updated != null && !updated.isObject())) {
            if (!Objects.equals(old, updated)) {
                sections.add(stem);
            }
            return sections;
        }
        addKeys(old, sections);
        addKeys(updated, sections);
        sections.removeIf(section -> Objects.equals(old == null ? null : old.get(section), updated == null ? null : updated.get(section)));
        return sections;
    }

    private static void addKeys(@Nullable JsonNode node, Set<String> into) {
        if (node == null) {
            return;
        }
        for (Iterator<String> names = node.fieldNames(); names.hasNext(); ) {
            into.add(names.next());
        }
    }

    @Nullable
    private static JsonNode parse(@Nullable String text, ObjectMapper parser) throws StudioException {
        if (text == null) {
            return null;
        }
        try {
            return parser.readTree(text);
        } catch (IOException invalid) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The file is not valid: " + invalid.getMessage());
        }
    }
}
