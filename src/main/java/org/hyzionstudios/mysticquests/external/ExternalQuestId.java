package org.hyzionstudios.mysticquests.external;

import java.util.Optional;

/**
 * Ids of mirrored quests on MysticQuests surfaces: {@code ext:<source>:<questId>}. The prefix keeps
 * them apart from MysticQuests' own quest ids, so a Journal action or a tracked id can be routed to the
 * mod that owns the quest.
 */
public final class ExternalQuestId {
    static final String PREFIX = "ext:";

    public record Parsed(String source, String questId) {
    }

    private ExternalQuestId() {
    }

    public static String of(String source, String questId) {
        return PREFIX + source + ":" + questId;
    }

    public static boolean isExternal(String id) {
        return id != null && id.startsWith(PREFIX);
    }

    /** The source and the mod's own id; empty for a MysticQuests id or a malformed one. */
    public static Optional<Parsed> parse(String id) {
        if (!isExternal(id)) {
            return Optional.empty();
        }
        String rest = id.substring(PREFIX.length());
        int split = rest.indexOf(':');
        if (split <= 0 || split == rest.length() - 1) {
            return Optional.empty();
        }
        return Optional.of(new Parsed(rest.substring(0, split), rest.substring(split + 1)));
    }
}
