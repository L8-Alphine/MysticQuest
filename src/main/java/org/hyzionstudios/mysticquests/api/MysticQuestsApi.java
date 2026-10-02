package org.hyzionstudios.mysticquests.api;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.condition.ConditionTypeRegistry;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.service.TargetingPreventionService;
import org.hyzionstudios.mysticquests.service.VisibilityService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestResult;
import org.hyzionstudios.mysticquests.state.EntityIndexService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.state.StateKey;
import org.hyzionstudios.mysticquests.state.StateScope;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;

/**
 * The stable entry point for other mods.
 *
 * <p>Everything here is reachable without compiling against MysticQuests internals: state in all five
 * scopes, entity state, visibility, NPC targeting, screen packets, quest queries, extension
 * registration, and change events. Internal services stay internal so they can be refactored without
 * breaking dependants.
 *
 * <p>Typical use from another mod's start-up:
 *
 * <pre>
 *   if (MysticQuestsApi.isAvailable()) {
 *       MysticQuestsApi api = MysticQuestsApi.get();
 *
 *       // A condition content can then use as { "type": "mymod:has_skill", "skill": "mining" }
 *       api.registry().registerCondition("mymod:has_skill",
 *               ctx -&gt; skills.has(ctx.playerId(), ctx.text("skill", "")));
 *
 *       // React to quest state without polling
 *       api.subscribe(StateEvents.TagChange.class, change -&gt; log(change.tag()));
 *   }
 * </pre>
 *
 * <p>Mods that would rather not add a compile-time dependency can reach the same surface reflectively
 * through {@code MysticQuestsApi.get()}.
 *
 * <p><b>Persistence:</b> tags and variables in every scope are persisted and survive restarts, written
 * asynchronously and coalesced per owner. Visibility overrides and targeting protection are
 * runtime-only and clear on disconnect, because they describe a scene rather than a save.
 *
 * <p><b>Threading:</b> state reads and writes are safe from any thread. Packet methods only reach an
 * online player and are no-ops otherwise. Event listeners run on the thread that posted the change,
 * usually the world thread mid-tick, so they should be quick.
 */
public final class MysticQuestsApi {
    private static volatile MysticQuestsApi instance;

    private final MysticStateStore state;
    private final EntityIndexService entityIndex;
    private final VisibilityService visibility;
    private final TargetingPreventionService targeting;
    private final QuestPacketService packets;
    private final PlayerQuestService quests;
    private final MysticQuestsRegistry registry;
    private final MysticQuestsEventBus eventBus;
    private final NarrativeRuntime narrative;

    public MysticQuestsApi(
            MysticStateStore state,
            EntityIndexService entityIndex,
            VisibilityService visibility,
            TargetingPreventionService targeting,
            QuestPacketService packets,
            PlayerQuestService quests,
            MysticQuestsRegistry registry,
            MysticQuestsEventBus eventBus,
            NarrativeRuntime narrative) {
        this.state = state;
        this.entityIndex = entityIndex;
        this.visibility = visibility;
        this.targeting = targeting;
        this.packets = packets;
        this.quests = quests;
        this.registry = registry;
        this.eventBus = eventBus;
        this.narrative = narrative;
    }

    /** Publishes this instance as the singleton. Called by the runtime once everything is wired. */
    public static void install(MysticQuestsApi api) {
        instance = api;
    }

    /** Withdraws the singleton on shutdown, so a stale reference cannot outlive the runtime. */
    public static void uninstall() {
        instance = null;
    }

    /** True when MysticQuests has finished starting and the API can be used. */
    public static boolean isAvailable() {
        return instance != null;
    }

    /**
     * @return the API facade
     * @throws IllegalStateException when MysticQuests is not running; check {@link #isAvailable()}
     *         first if that is possible in your load order
     */
    public static MysticQuestsApi get() {
        MysticQuestsApi api = instance;
        if (api == null) {
            throw new IllegalStateException("MysticQuests is not running");
        }
        return api;
    }

    // --- Player tags and variables ---

    public boolean hasTag(UUID player, String tag) {
        return state.hasTag(StateKey.of(StateScope.PLAYER, player.toString()), tag);
    }

    public boolean addTag(UUID player, String tag) {
        return state.addTag(StateKey.of(StateScope.PLAYER, player.toString()), tag);
    }

    public boolean removeTag(UUID player, String tag) {
        return state.removeTag(StateKey.of(StateScope.PLAYER, player.toString()), tag);
    }

    /** A live unmodifiable view of a player's tags. */
    public Set<String> tags(UUID player) {
        return state.tags(StateKey.of(StateScope.PLAYER, player.toString()));
    }

    @Nullable
    public String variable(UUID player, String key) {
        return state.variable(StateKey.of(StateScope.PLAYER, player.toString()), key);
    }

    public boolean setVariable(UUID player, String key, String value) {
        return state.setVariable(StateKey.of(StateScope.PLAYER, player.toString()), key, value);
    }

    public boolean removeVariable(UUID player, String key) {
        return state.removeVariable(StateKey.of(StateScope.PLAYER, player.toString()), key);
    }

    /** Atomically adds {@code delta} and returns the value afterwards. */
    public long incrementVariable(UUID player, String key, long delta) {
        return state.incrementVariable(StateKey.of(StateScope.PLAYER, player.toString()), key, delta);
    }

    /** A live unmodifiable view of a player's variables. */
    public Map<String, String> variables(UUID player) {
        return state.variables(StateKey.of(StateScope.PLAYER, player.toString()));
    }

    // --- Any scope ---

    /**
     * Tag access in any scope. Owner is a player UUID, an entity UUID, a {@code world:x:y:z} block
     * key, a {@code world:volumeId} volume key, or anything for {@link StateScope#GLOBAL}, which
     * collapses onto a single owner.
     */
    public boolean hasTag(StateScope scope, String owner, String tag) {
        return state.hasTag(StateKey.of(scope, owner), tag);
    }

    public boolean addTag(StateScope scope, String owner, String tag) {
        return state.addTag(StateKey.of(scope, owner), tag);
    }

    public boolean removeTag(StateScope scope, String owner, String tag) {
        return state.removeTag(StateKey.of(scope, owner), tag);
    }

    public Set<String> tags(StateScope scope, String owner) {
        return state.tags(StateKey.of(scope, owner));
    }

    @Nullable
    public String variable(StateScope scope, String owner, String key) {
        return state.variable(StateKey.of(scope, owner), key);
    }

    public boolean setVariable(StateScope scope, String owner, String key, String value) {
        return state.setVariable(StateKey.of(scope, owner), key, value);
    }

    public boolean removeVariable(StateScope scope, String owner, String key) {
        return state.removeVariable(StateKey.of(scope, owner), key);
    }

    public Map<String, String> variables(StateScope scope, String owner) {
        return state.variables(StateKey.of(scope, owner));
    }

    /** Drops every tag, variable, and metadata value for one owner. */
    public boolean clearOwner(StateScope scope, String owner) {
        return state.clearOwner(StateKey.of(scope, owner));
    }

    /** Owner ids currently holding state in a scope. Intended for tooling, not hot paths. */
    public Set<String> owners(StateScope scope) {
        return state.owners(scope);
    }

    // --- Entities ---

    public boolean hasEntityTag(UUID entity, String tag) {
        return hasTag(StateScope.ENTITY, entity.toString(), tag);
    }

    public boolean addEntityTag(UUID entity, String tag) {
        return addTag(StateScope.ENTITY, entity.toString(), tag);
    }

    public boolean removeEntityTag(UUID entity, String tag) {
        return removeTag(StateScope.ENTITY, entity.toString(), tag);
    }

    /** True when MysticQuests is currently tracking this entity's position. */
    public boolean isEntityTracked(UUID entity) {
        return entityIndex.isIndexed(entity);
    }

    /** A developer-facing display name for a tracked entity, or null when unset. */
    @Nullable
    public String entityDisplayName(UUID entity) {
        return entityIndex.displayName(entity);
    }

    public void setEntityDisplayName(UUID entity, @Nullable String displayName) {
        entityIndex.setDisplayName(entity, displayName);
    }

    // --- Visibility ---

    /**
     * Hides {@code target} — a player or any UUID-backed entity — from {@code viewer}.
     *
     * <p>Recorded under {@link VisibilityService.Source#EVENT}, so it is independent of any
     * condition-driven visibility rule and neither cancels the other.
     */
    public boolean hideFrom(UUID viewer, UUID target) {
        return visibility.hide(viewer, target, VisibilityService.Source.EVENT);
    }

    /** Releases a hide applied through {@link #hideFrom}. */
    public boolean showTo(UUID viewer, UUID target) {
        return visibility.show(viewer, target, VisibilityService.Source.EVENT);
    }

    /** True when {@code target} is hidden from {@code viewer} by any source. */
    public boolean isHiddenFrom(UUID viewer, UUID target) {
        return visibility.isHidden(viewer, target);
    }

    /** Everything currently hidden from a viewer. */
    public Set<UUID> hiddenFrom(UUID viewer) {
        return Set.copyOf(visibility.hiddenFrom(viewer));
    }

    /**
     * Whether MysticQuests currently lets {@code viewer} see {@code target}: the presentation answer,
     * which honours staff visibility bypass. {@link #isHiddenFrom} is the story's policy, and stays
     * true for a bypassing staff member.
     *
     * <p>This is the hook for mods that draw their own per-viewer presentation of a player, such as
     * nameplate glyphs. They should show it only when every system they consult allows it, for
     * example {@code MysticVanish.canSee && MysticQuests.canSee}. That is what keeps the layers
     * independent (§6.2 and §7 of the 2.0 specification): when a quest stops hiding someone, only
     * MysticQuests' answer changes, and a vanish still applies.
     *
     * <p>Kept stable for reflective callers: {@code isAvailable()}, {@code get()} and
     * {@code canSee(UUID, UUID)} are all they need.
     */
    public boolean canSee(UUID viewer, UUID target) {
        return visibility.canSee(viewer, target);
    }

    // --- NPC targeting ---

    /** Makes a player untargetable by NPCs until released. */
    public boolean preventTargeting(UUID player) {
        return targeting.protectPlayer(player);
    }

    public boolean allowTargeting(UUID player) {
        return targeting.unprotectPlayer(player);
    }

    public boolean isTargetingPrevented(UUID player) {
        return targeting.isProtected(player);
    }

    public Set<UUID> targetingProtectedPlayers() {
        return targeting.snapshotProtectedPlayers();
    }

    // --- Screen packets ---

    /** Shows a title and optional subtitle. Returns false when the player is offline. */
    public boolean sendTitle(UUID player, String title, @Nullable String subtitle, float durationSeconds) {
        return packets.sendTitle(player, title, subtitle, durationSeconds, 0.5f, 0.5f, "");
    }

    /** Shows an action-bar style notification. Returns false when the player is offline. */
    public boolean sendActionBar(UUID player, String message) {
        return packets.sendActionBar(player, message, "");
    }

    /** Switches the player's camera. Returns false when the player is offline. */
    public boolean setCamera(UUID player, QuestPacketService.CameraMode mode, boolean locked) {
        return packets.setCamera(player, mode, locked);
    }

    public boolean resetCamera(UUID player) {
        return packets.resetCamera(player);
    }

    // --- Quests ---

    public QuestResult startQuest(UUID player, String questId) {
        return quests.startQuest(player, questId);
    }

    public QuestResult completeQuest(UUID player, String questId) {
        return quests.completeQuest(player, questId);
    }

    /** Resolves MysticQuests percent-placeholders in text, as authored content would see them. */
    public String resolveText(UUID player, String packageId, String text) {
        return quests.resolveText(player, packageId, text);
    }

    // --- Extension and events ---

    /** Where event and condition types are registered. */
    public MysticQuestsRegistry registry() {
        return registry;
    }

    /**
     * Where narrative action and condition types are registered: those usable in puzzle outputs,
     * narrative transitions and condition trees. Ids must be namespaced outside {@code mysticquests},
     * and the first registration wins. Register during start-up, before content loads, so the
     * reload validates against your types. See docs/2.0/narrative-runtime.md.
     */
    public ActionTypeRegistry narrativeActions() {
        return narrative.actionTypes();
    }

    /** As {@link #narrativeActions()}, for condition types. */
    public ConditionTypeRegistry narrativeConditions() {
        return narrative.conditionTypes();
    }

    /**
     * Subscribes to a MysticQuests change event. Types live in
     * {@link org.hyzionstudios.mysticquests.event.StateEvents}.
     *
     * @return a handle whose {@code close()} unsubscribes; close it when your mod shuts down
     */
    public <E> AutoCloseable subscribe(Class<E> eventType, Consumer<E> listener) {
        return eventBus.subscribe(eventType, listener);
    }
}
