package org.hyzionstudios.mysticquests.api;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Where other mods add quest event and condition types.
 *
 * <p>Registered types are reachable from every place authored content can name a type — quest start
 * and complete events, rewards, conversation branches, objective gates, and trigger volume effects —
 * because the quest dispatcher falls through to this registry instead of logging an unknown type.
 * A content pack then uses a third-party type with no Java of its own:
 *
 * <pre>
 *   { "type": "mymod:grant_skill", "skill": "mining", "levels": 2 }
 * </pre>
 *
 * <p>Namespaced ids are strongly encouraged and built-in names are refused outright, so a mod cannot
 * quietly take over {@code tag} or {@code giveItem} and change what existing content means.
 *
 * <p>Handlers are looked up on a {@link ConcurrentHashMap} and registration is safe at any time, but
 * registering during plugin start is what makes a type available before content loads.
 */
public final class MysticQuestsRegistry {
    /**
     * Type names the dispatcher handles itself. Registering these would create a silent conflict
     * whose winner depends on mod load order, so it is rejected instead.
     */
    private static final Set<String> RESERVED_EVENT_TYPES = Set.of(
            "tag", "variable", "giveItem", "removeItem", "runCommand", "sendMessage",
            "startQuest", "completeQuest", "modifyMoney", "packetEffect", "triggerHyExtrasEffect",
            "notification", "folder", "party", "if", "cancelConversation", "cancelQuest", "ref",
            "addTag", "removeTag", "setVariable", "removeVariable", "incrementVariable",
            "hidePlayer", "showPlayer", "hideEntity", "showEntity", "spawnNpc", "despawnNpc",
            "preventTargeting", "allowTargeting", "setCamera", "sendTitle", "actionBar", "narrative");

    private static final Set<String> RESERVED_CONDITION_TYPES = Set.of(
            "tag", "variable", "notTag", "questCompleted", "questActive", "permission", "economy",
            "inConversation", "inParty", "partySize", "and", "or", "not", "ref",
            "playerHidden", "entityHidden", "targetingPrevented", "nearEntity", "narrative");

    private final Map<String, QuestEventHandler> events = new ConcurrentHashMap<>();
    private final Map<String, QuestConditionHandler> conditions = new ConcurrentHashMap<>();

    @Nullable
    private final HytaleLogger logger;

    public MysticQuestsRegistry(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Registers a quest event type.
     *
     * @return false when the name is blank, reserved by MysticQuests, or already taken by another
     *         mod — in which case nothing is replaced and a warning is logged
     */
    public boolean registerEvent(String type, QuestEventHandler handler) {
        return register("event", type, handler, events, RESERVED_EVENT_TYPES);
    }

    /**
     * Registers a quest condition type.
     *
     * @return false when the name is blank, reserved, or already taken
     */
    public boolean registerCondition(String type, QuestConditionHandler handler) {
        return register("condition", type, handler, conditions, RESERVED_CONDITION_TYPES);
    }

    /** Removes a previously registered event type. Call this when the owning mod shuts down. */
    public boolean unregisterEvent(String type) {
        return type != null && events.remove(type) != null;
    }

    /** Removes a previously registered condition type. */
    public boolean unregisterCondition(String type) {
        return type != null && conditions.remove(type) != null;
    }

    @Nullable
    public QuestEventHandler event(String type) {
        return events.get(type);
    }

    @Nullable
    public QuestConditionHandler condition(String type) {
        return conditions.get(type);
    }

    /** Registered event type names, for {@code /mquest} diagnostics and content validation. */
    public Set<String> eventTypes() {
        return Set.copyOf(events.keySet());
    }

    /** Registered condition type names, for {@code /mquest} diagnostics and content validation. */
    public Set<String> conditionTypes() {
        return Set.copyOf(conditions.keySet());
    }

    public void clear() {
        events.clear();
        conditions.clear();
    }

    private <H> boolean register(
            String kind,
            String type,
            H handler,
            Map<String, H> target,
            Set<String> reserved) {
        if (type == null || type.isBlank() || handler == null) {
            warn("Refused to register a MysticQuests " + kind + " with a blank type or null handler.");
            return false;
        }
        String name = type.trim();
        if (reserved.contains(name)) {
            warn("Refused to register MysticQuests " + kind + " '" + name
                    + "': that name is built in. Use a namespaced id such as 'mymod:" + name + "'.");
            return false;
        }
        if (target.putIfAbsent(name, handler) != null) {
            warn("Refused to register MysticQuests " + kind + " '" + name
                    + "': another mod already registered it.");
            return false;
        }
        return true;
    }

    private void warn(String message) {
        if (logger != null) {
            logger.at(Level.WARNING).log(message);
        }
    }
}
