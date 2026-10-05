package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.model.ObjectiveDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.NarrativeStateStore;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.SchemaRegistry;
import org.hyzionstudios.mysticquests.narrative.state.TagRecord;
import org.hyzionstudios.mysticquests.narrative.state.TagSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableSchema;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.storage.PlayerQuestData;

import javax.annotation.Nullable;
import java.io.IOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.function.Supplier;

/**
 * Reading and changing one player's quest state, for staff (Redesign Bible §9.1: "Admin mutation
 * tools require explicit permission, confirmation, reason and audit; read-only inspection is the
 * default").
 *
 * <p>One service behind every surface that changes player state — {@code /mquest player}, the
 * in-game admin page and the web Studio — so they cannot drift apart. Every change needs a reason,
 * goes through the same runtime services the game uses (so their own checks still apply) and is
 * written to the audit trail with what it was before and after.
 *
 * <p>What it covers, by part:
 * <ul>
 *   <li>{@link Part#QUESTS}: v1 quests — active, completed, abandoned, tracked — and their quest variables.</li>
 *   <li>{@link Part#TAGS} and {@link Part#VARIABLES}: v1 player-scope tags and variables.</li>
 *   <li>{@link Part#STORY_STATE}: 2.0 state owned by this player alone — player, per-quest and
 *       temporary scopes — except their saved preferences.</li>
 *   <li>{@link Part#SESSIONS}: the player's own active story sessions, which are abandoned so the
 *       next interaction starts the story fresh. Party sessions are shared and never cleared here.</li>
 *   <li>{@link Part#PREFERENCES}: their saved quest UI and audio settings.</li>
 * </ul>
 */
public final class PlayerStateAdmin {
    /** Needed to change a player's state from any surface; reading needs the debug permission. */
    public static final String PERMISSION = "mysticquests.command.admin.player";

    /** What a clear can remove. */
    public enum Part {
        QUESTS("v1 quests"),
        TAGS("v1 tags"),
        VARIABLES("v1 variables"),
        STORY_STATE("story state"),
        SESSIONS("story sessions"),
        PREFERENCES("preferences");

        private final String label;

        Part(String label) {
            this.label = label;
        }

        public String label() {
            return label;
        }

        /** Parses a part name as typed in a command or sent by the Studio; null when unknown. */
        @Nullable
        public static Part parse(String raw) {
            String key = raw == null ? "" : raw.trim().toLowerCase(Locale.ROOT).replace('-', '_');
            return switch (key) {
                case "quests", "quest" -> QUESTS;
                case "tags", "tag" -> TAGS;
                case "variables", "variable", "vars", "var" -> VARIABLES;
                case "story", "story_state", "narrative", "state" -> STORY_STATE;
                case "sessions", "session", "stories" -> SESSIONS;
                case "preferences", "prefs", "settings" -> PREFERENCES;
                default -> null;
            };
        }

        /** Everything except the player's own preferences: "start them over". */
        public static Set<Part> progress() {
            return EnumSet.of(QUESTS, TAGS, VARIABLES, STORY_STATE, SESSIONS);
        }
    }

    public enum QuestStatus { TRACKED, ACTIVE, COMPLETED, ABANDONED }

    public record ObjectiveLine(String id, String name, int current, int target) {
    }

    public record QuestLine(String questId, String name, QuestStatus status, String detail,
                            @Nullable Instant at, List<ObjectiveLine> objectives) {
    }

    /**
     * One 2.0 tag or variable.
     *
     * @param owner the owner's key, such as {@code player/<uuid>} or {@code quest/<uuid>|story:intro}
     * @param value the value as text; empty for a tag
     * @param preference whether it is one of the player's saved settings rather than story progress
     */
    public record StateLine(String owner, String scope, String id, String value, boolean preference) {
    }

    public record SessionLine(String id, String story, String status, boolean active, String node,
                              boolean party, @Nullable Instant updated) {
    }

    public record Snapshot(UUID player, String name, boolean online, List<QuestLine> quests,
                           Set<String> tags, Map<String, String> variables, boolean narrative,
                           List<StateLine> storyVariables, List<StateLine> storyTags,
                           List<SessionLine> sessions, List<String> problems) {
        public int activeQuests() {
            return (int) quests.stream().filter(quest -> quest.status() == QuestStatus.ACTIVE
                    || quest.status() == QuestStatus.TRACKED).count();
        }

        public int activeSessions() {
            return (int) sessions.stream().filter(SessionLine::active).count();
        }
    }

    /** What a change did, in words a staff member can act on. */
    public record Outcome(boolean ok, String message) {
        static Outcome ok(String message) {
            return new Outcome(true, message);
        }

        static Outcome refused(String message) {
            return new Outcome(false, message);
        }
    }

    /** Where audit entries go: the narrative audit trail when the story runtime runs, else the log. */
    @FunctionalInterface
    public interface Auditor {
        void record(String actor, String action, UUID player, @Nullable String sessionId,
                    String before, String after, String reason);
    }

    private final PlayerQuestService quests;
    private final ScopedStateService scopedState;
    private final Supplier<NarrativeRuntime> narrative;
    private final Auditor audit;
    private final Function<UUID, Optional<String>> names;
    private final Predicate<UUID> online;

    /**
     * @param narrative the story runtime, or a supplier of null when it is off
     * @param names a player's name when known (online players)
     * @param online whether a player is on this server now
     */
    public PlayerStateAdmin(PlayerQuestService quests, ScopedStateService scopedState, Supplier<NarrativeRuntime> narrative,
                            Auditor audit, Function<UUID, Optional<String>> names, Predicate<UUID> online) {
        this.quests = quests;
        this.scopedState = scopedState;
        this.narrative = narrative;
        this.audit = audit;
        this.names = names;
        this.online = online;
    }

    /** Whether a variable is one of the player's saved settings (quest UI, voice language, subtitles). */
    public static boolean isPreference(NamespacedId id) {
        return id.namespace().equals("mysticquests")
                && (id.path().startsWith("ui.") || id.path().equals("media.voice_locale") || id.path().equals("media.subtitles"));
    }

    // --- Reading ---

    /** Everything staff can see about a player's quest state. Reading changes nothing. */
    public Snapshot snapshot(UUID player) {
        PlayerQuestData data = quests.data(player);
        List<QuestLine> questLines = new ArrayList<>();
        Map<String, JournalEntry> active = new LinkedHashMap<>();
        for (JournalEntry entry : quests.journal(player)) {
            active.put(entry.questId(), entry);
        }
        for (Map.Entry<String, JournalEntry> entry : active.entrySet()) {
            JournalEntry journal = entry.getValue();
            List<ObjectiveLine> objectives = journal.objectives().stream()
                    .filter(objective -> !objective.objectiveId().isEmpty())
                    .map(objective -> new ObjectiveLine(objective.objectiveId(), objective.displayName(),
                            objective.current(), objective.target()))
                    .toList();
            boolean tracked = entry.getKey().equals(data.trackedQuestId());
            Instant started = data.activeQuests().get(entry.getKey()) == null ? null
                    : data.activeQuests().get(entry.getKey()).startedAt();
            questLines.add(new QuestLine(entry.getKey(), journal.displayName(),
                    tracked ? QuestStatus.TRACKED : QuestStatus.ACTIVE, journal.progressSummary(), started, objectives));
        }
        data.completedQuests().forEach((id, at) -> questLines.add(new QuestLine(id, questName(id),
                QuestStatus.COMPLETED, "Completed", at, List.of())));
        data.abandonedQuests().forEach((id, at) -> {
            if (!active.containsKey(id)) {
                questLines.add(new QuestLine(id, questName(id), QuestStatus.ABANDONED, "Abandoned", at, List.of()));
            }
        });

        Set<String> tags = new TreeSet<>(scopedState.tags("player", player.toString()));
        Map<String, String> variables = new TreeMap<>(scopedState.variables("player", player.toString()));
        StoryState story = story(player);
        return new Snapshot(player, names.apply(player).orElse(player.toString()), online.test(player), List.copyOf(questLines),
                tags, variables, story.narrative(), story.variables(), story.tags(), story.sessions(), story.problems());
    }

    /** The 2.0 half of a snapshot: the player's own story state and the sessions they take part in. */
    public record StoryState(boolean narrative, List<StateLine> variables, List<StateLine> tags,
                             List<SessionLine> sessions, List<String> problems) {
        public int activeSessions() {
            return (int) sessions.stream().filter(SessionLine::active).count();
        }
    }

    /** Reads the player's story state without changing or caching anything. */
    public StoryState story(UUID player) {
        List<String> problems = new ArrayList<>();
        List<StateLine> storyVariables = new ArrayList<>();
        List<StateLine> storyTags = new ArrayList<>();
        List<SessionLine> sessions = new ArrayList<>();
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return new StoryState(false, List.of(), List.of(), List.of(), List.of());
        }
        Instant now = Instant.now();
        NarrativeStateStore store = runtime.store();
        try {
            for (ScopeOwner owner : store.playerOwners(player)) {
                OwnerState state = store.peek(owner);
                state.variables().forEach((id, value) -> storyVariables.add(new StateLine(owner.key(),
                        owner.scope().id(), id.toString(), ValueCodec.toText(value), isPreference(id))));
                for (TagRecord tag : state.liveTags(now)) {
                    storyTags.add(new StateLine(owner.key(), owner.scope().id(), tag.id().toString(),
                            tag.expiresAt() == null ? "" : "until " + tag.expiresAt(), false));
                }
                if (store.isQuarantined(owner)) {
                    problems.add(owner.key() + " could not be read and is not being saved; see the server log.");
                }
            }
        } catch (IOException unreadable) {
            problems.add("Story state could not be listed: " + unreadable.getMessage());
        }
        for (SessionOwner owner : runtime.audiences().owners(player)) {
            for (QuestSession session : runtime.sessions().load(owner)) {
                sessions.add(new SessionLine(session.id(), session.storyKey(),
                        session.status().name().toLowerCase(Locale.ROOT), session.active(),
                        session.currentNode() == null ? "" : session.currentNode(),
                        !owner.equals(SessionOwner.player(player)), session.updatedAt()));
            }
        }
        storyVariables.sort(java.util.Comparator.comparing(StateLine::owner).thenComparing(StateLine::id));
        storyTags.sort(java.util.Comparator.comparing(StateLine::owner).thenComparing(StateLine::id));
        return new StoryState(true, List.copyOf(storyVariables), List.copyOf(storyTags), List.copyOf(sessions), List.copyOf(problems));
    }

    private String questName(String questId) {
        return quests.definition(questId).map(QuestDefinition::displayName).orElse(questId);
    }

    // --- Clearing ---

    /**
     * Clears the chosen parts of a player's state. Each part is cleared and audited on its own, so a
     * failure in one does not undo or hide the others; the outcome lists what happened to each.
     */
    public Outcome clear(String actor, UUID player, Set<Part> parts, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        if (parts.isEmpty()) {
            return Outcome.refused("Choose at least one part to clear.");
        }
        List<String> done = new ArrayList<>();
        boolean ok = true;
        for (Part part : EnumSet.copyOf(parts)) {
            if (part == Part.PREFERENCES && parts.contains(Part.STORY_STATE)) {
                continue; // cleared together with the story state, which shares their owner
            }
            try {
                done.add(clearPart(actor, player, part, reason, parts.contains(Part.PREFERENCES)));
            } catch (RuntimeException | IOException failure) {
                ok = false;
                done.add(part.label() + ": failed (" + failure.getMessage() + ")");
            }
        }
        settle(player);
        return new Outcome(ok, String.join("; ", done) + ".");
    }

    private String clearPart(String actor, UUID player, Part part, String reason, boolean includePreferences) throws IOException {
        switch (part) {
            case QUESTS -> {
                Snapshot before = snapshot(player);
                QuestResult result = quests.resetAllQuests(player);
                audit.record(actor, "player.clear.quests", player, null,
                        before.quests().size() + " quest records", "none", reason);
                return result.message();
            }
            case TAGS -> {
                Set<String> tags = scopedState.tags("player", player.toString());
                for (String tag : List.copyOf(tags)) {
                    scopedState.removeTag("player", player.toString(), tag);
                }
                quests.forgetLegacyState(player);
                audit.record(actor, "player.clear.tags", player, null, String.join(", ", tags), "none", reason);
                return "removed " + tags.size() + " v1 tags";
            }
            case VARIABLES -> {
                Map<String, String> variables = scopedState.variables("player", player.toString());
                for (String key : List.copyOf(variables.keySet())) {
                    scopedState.removeVariable("player", player.toString(), key);
                }
                quests.forgetLegacyState(player);
                audit.record(actor, "player.clear.variables", player, null, variables.toString(), "none", reason);
                return "removed " + variables.size() + " v1 variables";
            }
            case STORY_STATE -> {
                return clearStoryState(actor, player, reason, true, includePreferences);
            }
            case PREFERENCES -> {
                return clearStoryState(actor, player, reason, false, true);
            }
            case SESSIONS -> {
                NarrativeRuntime runtime = requireNarrative();
                int abandoned = 0;
                for (QuestSession session : runtime.sessions().load(SessionOwner.player(player))) {
                    if (session.active()) {
                        runtime.sessions().abandon(session);
                        audit.record(actor, "player.clear.session", player, session.id(), "active", "abandoned", reason);
                        abandoned++;
                    }
                }
                return abandoned == 1 ? "abandoned 1 story session" : "abandoned " + abandoned + " story sessions";
            }
            default -> throw new IllegalStateException("Unhandled part " + part);
        }
    }

    /**
     * Clears story progress, saved preferences, or both, from every owner that is this player's
     * alone. They share the player's owner, so clearing one keeps the other.
     */
    private String clearStoryState(String actor, UUID player, String reason, boolean progress, boolean preferences)
            throws IOException {
        NarrativeRuntime runtime = requireNarrative();
        NarrativeStateStore store = runtime.store();
        Instant now = Instant.now();
        int removed = 0;
        for (ScopeOwner owner : store.playerOwners(player)) {
            OwnerState live = store.state(owner);
            OwnerState kept = new OwnerState();
            if (!progress) {
                kept.replaceWith(live);
            }
            live.variables().forEach((id, value) -> {
                boolean preference = isPreference(id);
                if (preference && !preferences) {
                    kept.putVariable(id, value);
                } else if (preference) {
                    kept.removeVariable(id);
                }
            });
            int before = size(live, now);
            int after = size(kept, now);
            if (after != before) {
                live.replaceWith(kept);
                removed += before - after;
                audit.record(actor, progress ? "player.clear.story" : "player.clear.preferences", player, null,
                        before + " entries in " + owner.key(), after + " entries", reason);
            }
        }
        if (progress) {
            return "removed " + removed + " story state entries" + (preferences ? ", preferences included" : "");
        }
        return removed == 1 ? "reset 1 preference" : "reset " + removed + " preferences";
    }

    private static int size(OwnerState state, Instant now) {
        return state.variables().size() + state.liveTags(now).size() + state.triggerOverrides().size();
    }

    // --- v1 quests ---

    public Outcome startQuest(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.start", () -> quests.startQuest(player, questId));
    }

    public Outcome completeQuest(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.complete", () -> quests.completeQuest(player, questId));
    }

    public Outcome abandonQuest(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.abandon", () -> quests.abandonQuest(player, questId));
    }

    public Outcome resetQuest(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.reset", () -> quests.resetQuestState(player, questId));
    }

    /** Lets an abandoned quest be accepted again straight away. */
    public Outcome allowAgain(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.allow", () -> quests.clearAbandoned(player, questId));
    }

    public Outcome trackQuest(String actor, UUID player, String questId, String reason) {
        return questChange(actor, player, questId, reason, "quest.track", () -> quests.trackQuest(player, questId));
    }

    public Outcome setObjective(String actor, UUID player, String questId, String objectiveId, int value, String reason) {
        return questChange(actor, player, questId + " " + objectiveId, reason, "quest.objective",
                () -> quests.setObjectiveProgress(player, questId, objectiveId, value));
    }

    private Outcome questChange(String actor, UUID player, String target, String reason, String action,
                                Supplier<QuestResult> change) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        if (target == null || target.isBlank()) {
            return Outcome.refused("Name the quest.");
        }
        QuestResult result = change.get();
        if (result.success()) {
            audit.record(actor, action, player, null, "", target, reason);
        }
        return new Outcome(result.success(), result.message());
    }

    /** The objectives of an active quest, for choosing one to set. */
    public List<ObjectiveDefinition> objectives(String questId) {
        return quests.definition(questId).map(QuestDefinition::objectives).orElse(List.of());
    }

    // --- v1 tags and variables ---

    public Outcome addTag(String actor, UUID player, String tag, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        if (tag == null || tag.isBlank()) {
            return Outcome.refused("Name the tag.");
        }
        boolean added = scopedState.addTag("player", player.toString(), tag.trim());
        if (added) {
            audit.record(actor, "player.tag.add", player, null, "", tag.trim(), reason);
        }
        return added ? Outcome.ok("Added tag " + tag.trim() + ".") : Outcome.refused("The player already has tag " + tag.trim() + ".");
    }

    public Outcome removeTag(String actor, UUID player, String tag, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        boolean removed = scopedState.removeTag("player", player.toString(), tag);
        if (removed) {
            audit.record(actor, "player.tag.remove", player, null, tag, "", reason);
        }
        return removed ? Outcome.ok("Removed tag " + tag + ".") : Outcome.refused("The player does not have tag " + tag + ".");
    }

    public Outcome setVariable(String actor, UUID player, String key, String value, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        if (key == null || key.isBlank()) {
            return Outcome.refused("Name the variable.");
        }
        String before = scopedState.variables("player", player.toString()).getOrDefault(key.trim(), "");
        scopedState.setVariable("player", player.toString(), key.trim(), value == null ? "" : value);
        audit.record(actor, "player.variable.set", player, null, key.trim() + "=" + before, key.trim() + "=" + value, reason);
        return Outcome.ok("Set " + key.trim() + " to " + (value == null || value.isEmpty() ? "an empty value" : value) + ".");
    }

    public Outcome removeVariable(String actor, UUID player, String key, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        String before = scopedState.variables("player", player.toString()).get(key);
        boolean removed = scopedState.removeVariable("player", player.toString(), key);
        if (removed) {
            audit.record(actor, "player.variable.remove", player, null, key + "=" + before, "", reason);
        }
        return removed ? Outcome.ok("Removed variable " + key + ".") : Outcome.refused("The player has no variable " + key + ".");
    }

    // --- 2.0 story state ---

    /**
     * Sets a declared story variable in the player's own scope. Story variables are typed, so the
     * value is checked against the declaration; an undeclared variable is refused rather than stored
     * untyped, and the player's saved settings are changed through their own settings instead.
     */
    public Outcome setStoryVariable(String actor, UUID player, String rawId, String rawValue, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return Outcome.refused("The story runtime is not running on this server.");
        }
        Optional<NamespacedId> id = NamespacedId.tryParse(rawId == null ? null : rawId.trim());
        if (id.isEmpty()) {
            return Outcome.refused("Write the variable as namespace:name.");
        }
        if (isPreference(id.get())) {
            return Outcome.refused("That is one of the player's settings; they change it in their Journal.");
        }
        SchemaRegistry schemas = runtime.content().schemas();
        if (!schemas.isDeclaredVariable(id.get())) {
            return Outcome.refused("No story variable " + id.get() + " is declared in the loaded content.");
        }
        VariableSchema schema = schemas.variable(id.get());
        if (!schema.allows(VariableScope.PLAYER)) {
            return Outcome.refused(id.get() + " is declared for " + schema.scope().id() + " scope, not for one player.");
        }
        Coercion value = ValueCodec.coerceText(schema.type(), rawValue);
        if (!value.accepted()) {
            return Outcome.refused("Not a valid value: " + value.problem() + ".");
        }
        OwnerState state = runtime.store().state(ScopeOwner.player(player));
        QuestValue previous = state.variable(id.get());
        state.putVariable(id.get(), value.value());
        audit.record(actor, "player.story.variable.set", player, null,
                id.get() + "=" + (previous == null ? "" : ValueCodec.toText(previous)),
                id.get() + "=" + ValueCodec.toText(value.value()), reason);
        settle(player);
        return Outcome.ok("Set " + id.get() + " to " + ValueCodec.toText(value.value()) + ".");
    }

    /** Removes a story variable from one of the player's own owners (player, quest or temporary scope). */
    public Outcome removeStoryVariable(String actor, UUID player, String ownerKey, String rawId, String reason) {
        return storyRemoval(actor, player, ownerKey, rawId, reason, false);
    }

    /** Removes a story tag from one of the player's own owners. */
    public Outcome removeStoryTag(String actor, UUID player, String ownerKey, String rawId, String reason) {
        return storyRemoval(actor, player, ownerKey, rawId, reason, true);
    }

    private Outcome storyRemoval(String actor, UUID player, String ownerKey, String rawId, String reason, boolean tag) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return Outcome.refused("The story runtime is not running on this server.");
        }
        Optional<NamespacedId> id = NamespacedId.tryParse(rawId);
        if (id.isEmpty()) {
            return Outcome.refused("Not a story id: " + rawId);
        }
        ScopeOwner owner;
        try {
            owner = runtime.store().playerOwners(player).stream()
                    .filter(candidate -> candidate.key().equals(ownerKey))
                    .findFirst().orElse(null);
        } catch (IOException unreadable) {
            return Outcome.refused("Story state could not be listed: " + unreadable.getMessage());
        }
        if (owner == null) {
            return Outcome.refused("That state does not belong to this player.");
        }
        OwnerState state = runtime.store().state(owner);
        boolean removed;
        String before;
        if (tag) {
            before = state.tag(id.get(), Instant.now()).map(record -> record.id().toString()).orElse("");
            removed = state.removeTag(id.get(), Instant.now());
        } else {
            QuestValue value = state.variable(id.get());
            before = value == null ? "" : id.get() + "=" + ValueCodec.toText(value);
            removed = state.removeVariable(id.get());
        }
        if (!removed) {
            return Outcome.refused((tag ? "No tag " : "No variable ") + id.get() + " in " + ownerKey + ".");
        }
        audit.record(actor, tag ? "player.story.tag.remove" : "player.story.variable.remove", player, null, before, "", reason);
        settle(player);
        return Outcome.ok("Removed " + id.get() + " from " + ownerKey + ".");
    }

    /** Adds a declared story tag in the player's own scope. */
    public Outcome addStoryTag(String actor, UUID player, String rawId, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return Outcome.refused("The story runtime is not running on this server.");
        }
        Optional<NamespacedId> id = NamespacedId.tryParse(rawId == null ? null : rawId.trim());
        if (id.isEmpty()) {
            return Outcome.refused("Write the tag as namespace:name.");
        }
        SchemaRegistry schemas = runtime.content().schemas();
        if (!schemas.isDeclaredTag(id.get())) {
            return Outcome.refused("No story tag " + id.get() + " is declared in the loaded content.");
        }
        TagSchema schema = schemas.tag(id.get());
        if (!schema.allows(VariableScope.PLAYER)) {
            return Outcome.refused(id.get() + " is declared for " + schema.scope().id() + " scope, not for one player.");
        }
        Instant now = Instant.now();
        Instant expires = schema.defaultTtl() == null ? null : now.plus(schema.defaultTtl());
        boolean added = runtime.store().state(ScopeOwner.player(player)).putTag(new TagRecord(id.get(), now, expires, "admin"), now);
        if (added) {
            audit.record(actor, "player.story.tag.add", player, null, "", id.get().toString(), reason);
        }
        settle(player);
        return added ? Outcome.ok("Added story tag " + id.get() + ".") : Outcome.refused("The player already has story tag " + id.get() + ".");
    }

    /**
     * Restarts a story for the player: its active session — theirs or their party's — is abandoned,
     * so the next interaction opens it fresh. For a party story that restarts it for the whole party.
     */
    public Outcome restartStory(String actor, UUID player, String storyKey, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return Outcome.refused("The story runtime is not running on this server.");
        }
        Optional<String> session = runtime.restartStory(player, storyKey);
        if (session.isEmpty()) {
            return Outcome.refused("The story " + storyKey + " is not active for this player.");
        }
        audit.record(actor, "story.restart", player, session.get(), "active", "abandoned", reason);
        return Outcome.ok("Restarted " + storyKey + "; the next interaction starts it fresh.");
    }

    /** Rewinds the player's story to a checkpoint (§21.1). */
    public Outcome rewind(String actor, UUID player, String label, String reason) {
        Outcome refused = requireReason(reason);
        if (refused != null) {
            return refused;
        }
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return Outcome.refused("The story runtime is not running on this server.");
        }
        Optional<String> session = runtime.rewind(player, label);
        if (session.isEmpty()) {
            return Outcome.refused("No active story of this player has a checkpoint named " + label + ".");
        }
        audit.record(actor, "checkpoint.rewind", player, session.get(), "", label, reason);
        return Outcome.ok("Rewound to checkpoint " + label + ".");
    }

    // --- Helpers ---

    @Nullable
    private static Outcome requireReason(String reason) {
        return reason == null || reason.isBlank() ? Outcome.refused("Give a reason; every change to a player is audited.") : null;
    }

    private NarrativeRuntime requireNarrative() {
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            throw new IllegalStateException("the story runtime is not running");
        }
        return runtime;
    }

    /**
     * Writes story changes now, and unloads an offline player's owners again so looking them up for
     * an edit does not keep them in memory. An online player's owners stay loaded, as they always do.
     */
    private void settle(UUID player) {
        NarrativeRuntime runtime = narrative.get();
        if (runtime == null) {
            return;
        }
        runtime.store().flush();
        if (!online.test(player)) {
            String questPrefix = player + "|";
            runtime.store().evict(owner -> owner.equals(ScopeOwner.player(player))
                    || owner.scope() == VariableScope.QUEST && owner.ownerId().startsWith(questPrefix));
        }
    }
}
