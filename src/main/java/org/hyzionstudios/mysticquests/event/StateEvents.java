package org.hyzionstudios.mysticquests.event;

import org.hyzionstudios.mysticquests.state.StateScope;

import javax.annotation.Nullable;
import java.util.UUID;

/**
 * State-change events posted on the {@link MysticQuestsEventBus}.
 *
 * <p>These let quest services and third-party mods react to tag and variable changes without
 * polling. The visibility service in particular depends on them: it indexes hider rules by the
 * tags and variables they read, then reconciles only the players whose inputs actually changed.
 *
 * <p>{@code name} is {@code null} for {@link ChangeType#CLEAR}, which covers the whole owner.
 */
public final class StateEvents {
    private StateEvents() {
    }

    public enum ChangeType { ADD, REMOVE, SET, CLEAR }

    /**
     * A tag was added to, removed from, or cleared on one owner.
     *
     * @param owner the owner id within {@code scope} — a player UUID for {@link StateScope#PLAYER},
     *         an entity UUID, a block key, a volume key, or the global owner
     */
    public record TagChange(StateScope scope, String owner, @Nullable String tag, ChangeType type) {
    }

    /** A variable was set, removed, or cleared on one owner. */
    public record VariableChange(
            StateScope scope,
            String owner,
            @Nullable String key,
            @Nullable String value,
            ChangeType type) {
    }

    /**
     * A viewer's view of another player or entity changed. {@code hidden} is the new state.
     * Posted after the change has been applied to the engine, not before.
     */
    public record VisibilityChange(UUID viewer, UUID target, boolean hidden) {
    }

    /**
     * A staff member's presentation bypass switched. While on, they see what quests hide from them;
     * no {@link VisibilityChange} is posted for that, because the story's hides are unchanged.
     */
    public record VisibilityBypassChange(UUID viewer, boolean bypassing) {
    }

    /** A player gained or lost protection from NPC targeting. */
    public record TargetingChange(UUID player, boolean protectedFromTargeting) {
    }
}
