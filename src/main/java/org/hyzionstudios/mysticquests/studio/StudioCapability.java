package org.hyzionstudios.mysticquests.studio;

import java.util.EnumSet;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Stream;

/**
 * What a Studio user may do (2.0 specification §19.2), each backed by a server permission
 * {@code mysticquests.studio.<name>}. Checked on the server for every request.
 *
 * <p>The content capabilities are separable on purpose: a writer with {@link #DIALOGUE} edits
 * conversations without touching rewards, a builder with {@link #PUZZLES} and {@link #TRIGGERS}
 * manages puzzles and world presentation, and only {@link #PUBLISH} puts a release live.
 * {@link #EDIT} covers every content section; any capability that changes or publishes content
 * includes {@link #VIEW}; {@link #PLAYERS} includes {@link #LIVE}, since changing a player means
 * seeing them first; {@link #ADMIN} (or {@code mysticquests.admin}) covers everything.
 */
public enum StudioCapability {
    LOGIN,
    VIEW,
    EDIT,
    DIALOGUE,
    AUDIO,
    PUZZLES,
    TRIGGERS,
    LIVE,
    /** Change a player's quest and story state from the Players page; reading it needs {@link #LIVE}. */
    PLAYERS,
    PUBLISH,
    ADMIN;

    /** Checks one permission for a player, online or not. */
    @FunctionalInterface
    public interface Permissions {
        boolean has(UUID player, String permission);
    }

    public String permission() {
        return "mysticquests.studio." + name().toLowerCase(Locale.ROOT);
    }

    /** Whether {@code player} holds this capability, directly or through edit or admin rights. */
    public boolean grantedTo(UUID player, Permissions permissions) {
        if (permissions.has(player, "mysticquests.admin") || permissions.has(player, ADMIN.permission())
                || permissions.has(player, permission())) {
            return true;
        }
        return switch (this) {
            case DIALOGUE, AUDIO, PUZZLES, TRIGGERS -> permissions.has(player, EDIT.permission());
            case LIVE -> permissions.has(player, PLAYERS.permission());
            // Anyone who may change or publish content may also read it.
            case VIEW -> Stream.of(EDIT, DIALOGUE, AUDIO, PUZZLES, TRIGGERS, LIVE, PUBLISH)
                    .anyMatch(capability -> permissions.has(player, capability.permission()));
            default -> false;
        };
    }

    /** Every capability {@code player} holds, for the Studio to show only what they can use. */
    public static Set<StudioCapability> of(UUID player, Permissions permissions) {
        Set<StudioCapability> granted = EnumSet.noneOf(StudioCapability.class);
        for (StudioCapability capability : values()) {
            if (capability.grantedTo(player, permissions)) {
                granted.add(capability);
            }
        }
        return granted;
    }
}
