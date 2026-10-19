package org.hyzionstudios.mysticquests.integration.external;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.api.QuestActionContext;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.external.ExternalQuest;
import org.hyzionstudios.mysticquests.external.ExternalQuestBook;
import org.hyzionstudios.mysticquests.external.ExternalQuestId;
import org.hyzionstudios.mysticquests.external.ExternalQuestSnapshot;
import org.hyzionstudios.mysticquests.external.ExternalSignals;
import org.hyzionstudios.mysticquests.integration.IntegrationStatus;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge.PlayerHandle;
import org.hyzionstudios.mysticquests.integration.hud.HudOverrideFilter;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.QuestResult;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.ui.HiddenHudKeys;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.OptionalLong;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Predicate;
import java.util.logging.Level;

/**
 * Mirrors other mods' quests into MysticQuests (docs/integrations/external-quests.md).
 *
 * <p>The other mod stays in charge: it counts progress, pays rewards and runs its own quest givers.
 * This service reads each player's state from it on their world thread
 * ({@link org.hyzionstudios.mysticquests.hytale.ExternalQuestSyncSystem}), keeps the result in an
 * {@link ExternalQuestBook}, and from there:
 *
 * <ul>
 *   <li>the HUD and the Journal show mirrored quests beside MysticQuests' own, and the tracker can
 *       follow one;</li>
 *   <li>the mod's own quest tracker HUD is hidden, so there is one tracker on screen;</li>
 *   <li>content can ask about mirrored quests ({@code endgame:questCompleted}) and start, track or
 *       abandon them ({@code endgame:acceptQuest}), and {@code signal} objectives count what happens
 *       in the mod ({@code endgame:quest_completed:story_echo}).</li>
 * </ul>
 *
 * <p>Each bridge compiles against its mod and is loaded by name only once that mod is present, so a
 * server without it never loads a class that names it.
 */
public final class ExternalQuestService {
    /** What MysticQuests knows how to mirror: the bridge id, the mod's name, a class the mod ships, and our bridge. */
    private record Known(String id, String name, String probeClass, String bridgeClass) {
    }

    private static final List<Known> KNOWN = List.of(
            new Known("endgame", "Endgame & QoL", "endgame.plugin.EndgameQoL",
                    "org.hyzionstudios.mysticquests.integration.endgame.EndgameQuestBridge"),
            new Known("ziggfreed", "ZiggfreedCommon / MMOSkillTree", "com.ziggfreed.common.progress.runtime.ProgressionRuntime",
                    "org.hyzionstudios.mysticquests.integration.ziggfreed.ZiggfreedQuestBridge"));

    private final MysticQuestsConfig.ExternalQuestsConfig config;
    private final boolean questHud;
    private final PlayerSessionService sessions;
    private final Consumer<QuestSignal> signals;
    private final HudOverrideFilter hudFilter;
    private final HytaleLogger logger;
    private final ExternalQuestBook book = new ExternalQuestBook();
    private final List<ExternalQuestBridge> bridges = new CopyOnWriteArrayList<>();
    /** Written at attach and from a world thread when a bridge is dropped; read by {@code /mq integrations}. */
    private final Map<String, IntegrationStatus.Entry> status = Collections.synchronizedMap(new LinkedHashMap<>());
    private final Map<UUID, Long> nextSync = new ConcurrentHashMap<>();
    private final Set<String> registeredConditions = new LinkedHashSet<>();
    private final Set<String> registeredEvents = new LinkedHashSet<>();
    private volatile Consumer<UUID> onPlayerChanged = player -> {
    };
    private MysticQuestsRegistry registry;

    /**
     * @param questHud whether MysticQuests' own HUD is on; the mods' trackers are only hidden when it is
     * @param signals where forwarded signals go, the quest signal bus
     */
    public ExternalQuestService(MysticQuestsConfig.ExternalQuestsConfig config, boolean questHud,
                                PlayerSessionService sessions, Consumer<QuestSignal> signals, HytaleLogger logger) {
        this.config = config;
        this.questHud = questHud;
        this.sessions = sessions;
        this.signals = signals;
        this.logger = logger;
        this.hudFilter = new HudOverrideFilter(logger);
        book.onChange(this::changed);
    }

    /** Called with a player whose mirrored quests changed, after the book has the change: the HUD reconciles. */
    public void onPlayerChanged(Consumer<UUID> listener) {
        this.onPlayerChanged = listener;
    }

    public ExternalQuestBook book() {
        return book;
    }

    /** Loads the bridges whose mods are running, hides the HUDs they replace and registers content types. */
    public void attach(MysticQuestsRegistry registry) {
        this.registry = registry;
        for (Known known : KNOWN) {
            boolean enabled = switch (known.id()) {
                case "endgame" -> Boolean.TRUE.equals(config.endgame());
                case "ziggfreed" -> Boolean.TRUE.equals(config.ziggfreed());
                default -> false;
            };
            if (!enabled) {
                status.put(known.id(), new IntegrationStatus.Entry(known.name(), IntegrationStatus.State.DISABLED,
                        "its quests stay in its own book and tracker"));
                continue;
            }
            load(known).ifPresent(bridges::add);
        }
        Set<String> hidden = new LinkedHashSet<>(config.hiddenHuds());
        if (replacesTrackers()) {
            for (ExternalQuestBridge bridge : bridges) {
                hidden.addAll(bridge.trackerHudKeys());
            }
        }
        hudFilter.hide(HiddenHudKeys.of(hidden));
        for (ExternalQuestBridge bridge : bridges) {
            registerContent(bridge);
            if (Boolean.TRUE.equals(config.forwardSignals())) {
                try {
                    bridge.forwardSignals(this::publish);
                } catch (RuntimeException | LinkageError failure) {
                    logger.at(Level.WARNING).withCause(failure).log("Could not forward " + bridge.displayName() + " moments as signals.");
                }
            }
        }
    }

    /** Whether MysticQuests' tracker takes over from the mods' own. */
    private boolean replacesTrackers() {
        return questHud && Boolean.TRUE.equals(config.unifiedTracker()) && Boolean.TRUE.equals(config.hideModTrackers());
    }

    public boolean unifiedTracker() {
        return Boolean.TRUE.equals(config.unifiedTracker()) && !bridges.isEmpty();
    }

    private Optional<ExternalQuestBridge> load(Known known) {
        ClassLoader loader = ExternalQuestService.class.getClassLoader();
        try {
            Class.forName(known.probeClass(), false, loader);
        } catch (ClassNotFoundException | LinkageError absent) {
            status.put(known.id(), new IntegrationStatus.Entry(known.name(), IntegrationStatus.State.ABSENT, "not installed"));
            return Optional.empty();
        }
        try {
            ExternalQuestBridge bridge = (ExternalQuestBridge) Class.forName(known.bridgeClass(), true, loader)
                    .getConstructor(HytaleLogger.class).newInstance(logger);
            status.put(known.id(), new IntegrationStatus.Entry(known.name(), IntegrationStatus.State.ACTIVE, "quests mirrored"));
            logger.at(Level.INFO).log("Mirroring " + known.name() + " quests into MysticQuests.");
            return Optional.of(bridge);
        } catch (ClassNotFoundException notBuilt) {
            status.put(known.id(), new IntegrationStatus.Entry(known.name(), IntegrationStatus.State.PARTIAL,
                    "installed, but this MysticQuests build was made without it"));
        } catch (ReflectiveOperationException | LinkageError | RuntimeException incompatible) {
            status.put(known.id(), new IntegrationStatus.Entry(known.name(), IntegrationStatus.State.PARTIAL,
                    "installed, but this version does not match what MysticQuests was built against"));
            logger.at(Level.WARNING).withCause(incompatible).log("Could not mirror " + known.name() + " quests.");
        }
        return Optional.empty();
    }

    // --- Content types ---

    private void registerContent(ExternalQuestBridge bridge) {
        String id = bridge.id();
        condition(id + ":questCompleted", context -> book.isCompleted(context.playerId(), id, context.text("quest", "")));
        condition(id + ":questActive", context -> book.isActive(context.playerId(), id, context.text("quest", "")));
        condition(id + ":questReady", context -> book.snapshot(context.playerId(), id)
                .flatMap(snapshot -> snapshot.quest(context.text("quest", "")))
                .map(quest -> quest.status() == ExternalQuest.Status.READY).orElse(false));
        condition(id + ":stat", context -> {
            OptionalLong value = book.stat(context.playerId(), id, context.text("stat", ""));
            long min = context.definition().longValue("min", Long.MIN_VALUE);
            long max = context.definition().longValue("max", Long.MAX_VALUE);
            return value.isPresent() && value.getAsLong() >= min && value.getAsLong() <= max;
        });
        event(id + ":acceptQuest", context -> onWorld(context.playerId(), player -> {
            if (bridge.accept(player, context.text("quest", ""))) {
                requestSync(player.id());
            }
        }));
        event(id + ":abandonQuest", context -> onWorld(context.playerId(), player -> {
            if (bridge.abandon(player, context.text("quest", ""))) {
                requestSync(player.id());
            }
        }));
        event(id + ":trackQuest", context -> onWorld(context.playerId(), player -> {
            String questId = context.text("quest", "");
            bridge.track(player, questId);
            book.focus(player.id(), ExternalQuestId.of(id, questId));
            requestSync(player.id());
        }));
        try {
            Set<String> own = bridge.registerContent(registry, book, this::onWorld);
            registeredEvents.addAll(own);
            registeredConditions.addAll(own);
        } catch (RuntimeException | LinkageError failure) {
            logger.at(Level.WARNING).withCause(failure).log("Could not register " + bridge.displayName() + " content types.");
        }
    }

    private void condition(String type, Predicate<QuestActionContext> test) {
        if (registry.registerCondition(type, test::test)) {
            registeredConditions.add(type);
        }
    }

    private void event(String type, Consumer<QuestActionContext> action) {
        if (registry.registerEvent(type, action::accept)) {
            registeredEvents.add(type);
        }
    }

    // --- Sync ---

    /**
     * Re-reads every mod for one player when they are due. On the player's world thread.
     *
     * @param now the current time in milliseconds
     */
    public void sync(PlayerHandle player, long now) {
        if (bridges.isEmpty()) {
            return;
        }
        UUID id = player.id();
        Long due = nextSync.get(id);
        if (due != null && now < due) {
            return;
        }
        nextSync.put(id, now + config.syncIntervalMillis());
        syncNow(player);
    }

    private void syncNow(PlayerHandle player) {
        for (ExternalQuestBridge bridge : bridges) {
            ExternalQuestSnapshot snapshot;
            try {
                snapshot = bridge.snapshot(player);
            } catch (LinkageError incompatible) {
                // The mod changed under us: stop asking rather than fail every second for every player.
                bridges.remove(bridge);
                book.removeSource(bridge.id());
                status.put(bridge.id(), new IntegrationStatus.Entry(bridge.displayName(), IntegrationStatus.State.PARTIAL,
                        "stopped: this version does not match what MysticQuests was built against"));
                logger.at(Level.WARNING).withCause(incompatible).log("Stopped mirroring " + bridge.displayName() + " quests.");
                continue;
            } catch (RuntimeException failure) {
                logger.at(Level.FINE).withCause(failure).log("Could not read " + bridge.displayName() + " quests for " + player.id() + ".");
                continue;
            }
            book.put(player.id(), snapshot);
        }
    }

    /** Re-reads a player at the next opportunity, after something MysticQuests did to a mirrored quest. */
    public void requestSync(UUID player) {
        nextSync.remove(player);
    }

    private void changed(ExternalQuestBook.Change change) {
        if (Boolean.TRUE.equals(config.forwardSignals())) {
            for (ExternalSignals.Signal signal : ExternalSignals.of(change)) {
                publish(change.player(), signal.name(), signal.amount());
            }
        }
        onPlayerChanged.accept(change.player());
    }

    private void publish(UUID player, String signal, int amount) {
        try {
            signals.accept(QuestSignal.simple(player, "signal", signal, amount));
        } catch (RuntimeException failure) {
            logger.at(Level.WARNING).withCause(failure).log("Could not publish signal " + signal + ".");
        }
    }

    // --- Player actions ---

    /** Runs on the player's world thread, if they are online. */
    private void onWorld(UUID player, Consumer<PlayerHandle> action) {
        sessions.runOnWorld(player, (ref, store) -> handle(ref, store).ifPresent(action));
    }

    private static Optional<PlayerHandle> handle(Ref<EntityStore> ref, Store<EntityStore> store) {
        if (ref == null || !ref.isValid()) {
            return Optional.empty();
        }
        PlayerRef playerRef = store.getComponent(ref, PlayerRef.getComponentType());
        Player player = store.getComponent(ref, Player.getComponentType());
        return playerRef == null || player == null ? Optional.empty() : Optional.of(new PlayerHandle(ref, store, playerRef, player));
    }

    /**
     * A Journal action on a mirrored quest — {@code track}, {@code untrack} or {@code abandon} — done
     * at once, since Journal events arrive on the player's world thread; the player is re-read
     * straight away so the Journal's refresh shows the result.
     */
    public QuestResult act(Ref<EntityStore> ref, Store<EntityStore> store, String key, String action) {
        Optional<ExternalQuestId.Parsed> parsed = ExternalQuestId.parse(key);
        Optional<ExternalQuestBridge> bridge = parsed.flatMap(found -> bridge(found.source()));
        Optional<PlayerHandle> player = handle(ref, store);
        if (parsed.isEmpty() || bridge.isEmpty() || player.isEmpty()) {
            return QuestResult.failure("That quest is not available right now.");
        }
        String questId = parsed.get().questId();
        String title = book.find(player.get().id(), key).map(ExternalQuest::title).orElse(questId);
        QuestResult result;
        try {
            result = switch (action) {
                case "track" -> {
                    bridge.get().track(player.get(), questId);
                    book.focus(player.get().id(), key);
                    yield QuestResult.success("Tracking " + title + ".");
                }
                case "untrack" -> {
                    bridge.get().untrack(player.get(), questId);
                    book.focusMysticQuests(player.get().id());
                    yield QuestResult.success("Stopped tracking " + title + ".");
                }
                case "abandon" -> bridge.get().abandon(player.get(), questId)
                        ? QuestResult.success("Abandoned " + title + ".")
                        : QuestResult.failure(bridge.get().displayName() + " does not let you abandon " + title + ".");
                default -> QuestResult.failure("Unknown action.");
            };
        } catch (RuntimeException | LinkageError failure) {
            logger.at(Level.WARNING).withCause(failure).log("Journal action " + action + " on " + key + " failed.");
            return QuestResult.failure("That did not work. Try again from " + bridge.get().displayName() + "'s own book.");
        }
        syncNow(player.get());
        onPlayerChanged.accept(player.get().id());
        return result;
    }

    private Optional<ExternalQuestBridge> bridge(String id) {
        return bridges.stream().filter(bridge -> bridge.id().equals(id)).findFirst();
    }

    /** A mod's own quest book, reachable from a tab on MysticQuests' quest pages. */
    public record Book(String source, String label) {
    }

    /** The books of the mods mirrored right now, in load order. */
    public List<Book> books() {
        List<Book> books = new ArrayList<>();
        for (ExternalQuestBridge bridge : bridges) {
            try {
                String label = bridge.bookLabel();
                if (label != null && !label.isBlank()) {
                    books.add(new Book(bridge.id(), label));
                }
            } catch (RuntimeException | LinkageError failure) {
                logger.at(Level.FINE).withCause(failure).log("Could not name the " + bridge.displayName() + " quest book.");
            }
        }
        return books;
    }

    /** Opens a mod's own quest book for the player, replacing the page they have open. On their world thread. */
    public boolean openBook(Ref<EntityStore> ref, Store<EntityStore> store, String source) {
        Optional<ExternalQuestBridge> bridge = bridge(source);
        Optional<PlayerHandle> player = handle(ref, store);
        if (bridge.isEmpty() || player.isEmpty()) {
            return false;
        }
        try {
            return bridge.get().openBook(player.get());
        } catch (RuntimeException | LinkageError failure) {
            logger.at(Level.WARNING).withCause(failure).log("Could not open the " + bridge.get().displayName() + " quest book.");
            return false;
        }
    }

    /** Display name of the mod behind a source id, for the Journal's context line. */
    public String sourceName(String source) {
        return bridge(source).map(ExternalQuestBridge::displayName).orElse(source);
    }

    // --- Status ---

    /** Every HUD key MysticQuests keeps off players' screens. */
    public HiddenHudKeys hiddenHuds() {
        return hudFilter.hidden();
    }

    public List<IntegrationStatus.Entry> status() {
        List<IntegrationStatus.Entry> entries = new ArrayList<>();
        Map<String, IntegrationStatus.Entry> current;
        synchronized (status) {
            current = new LinkedHashMap<>(status);
        }
        current.forEach((id, entry) -> {
            Optional<ExternalQuestBridge> bridge = bridge(id);
            if (entry.state() == IntegrationStatus.State.ACTIVE && bridge.isPresent()) {
                StringBuilder detail = new StringBuilder("quests mirrored");
                Map<String, Integer> counts = safeCounts(bridge.get());
                counts.forEach((name, count) -> detail.append("; ").append(count).append(' ').append(name));
                Set<String> trackers = bridge.get().trackerHudKeys();
                if (replacesTrackers() && !trackers.isEmpty()) {
                    detail.append("; its tracker is replaced by MysticQuests'");
                }
                entries.add(new IntegrationStatus.Entry(entry.name(), entry.state(), detail.toString()));
            } else {
                entries.add(entry);
            }
        });
        HiddenHudKeys hidden = hudFilter.hidden();
        if (!hidden.isEmpty()) {
            entries.add(new IntegrationStatus.Entry("Hidden HUD layers", IntegrationStatus.State.ACTIVE,
                    String.join(", ", hidden.describe())));
        }
        return entries;
    }

    private Map<String, Integer> safeCounts(ExternalQuestBridge bridge) {
        try {
            return bridge.counts();
        } catch (RuntimeException | LinkageError failure) {
            return Map.of();
        }
    }

    /** The mirrored quests a player has, with the source's name, for {@code /mq debug}. */
    public List<String> describe(UUID player) {
        List<String> lines = new ArrayList<>();
        for (ExternalQuestSnapshot snapshot : book.snapshots(player)) {
            lines.add(sourceName(snapshot.source()) + ": " + snapshot.active().size() + " active, "
                    + snapshot.completed().size() + " completed (" + snapshot.completions().size() + " titled), tracked "
                    + snapshot.tracked());
            for (ExternalQuest quest : snapshot.active()) {
                lines.add("  " + quest.key() + " — " + quest.title() + " [" + quest.status() + "] step "
                        + quest.stepIndex() + "/" + quest.stepCount());
            }
        }
        return lines;
    }

    /** Mirrored quests as the Journal shows them, for a player. */
    public <T> List<T> active(UUID player, Function<ExternalQuest, T> mapper) {
        return book.active(player).stream().map(mapper).toList();
    }

    /** Mirrored quests the player has finished, as the Journal's completed section lists them. */
    public <T> List<T> finished(UUID player, Function<ExternalQuestBook.Finished, T> mapper) {
        return book.finished(player).stream().map(mapper).toList();
    }

    /** The Journal category a mod's quests are filed under. */
    public String category(String source) {
        return bridge(source).map(ExternalQuestBridge::category).orElse(source);
    }

    @Nullable
    public ExternalQuest trackerQuest(UUID player, boolean mysticQuestTracked) {
        if (!Boolean.TRUE.equals(config.unifiedTracker())) {
            return null;
        }
        return book.trackerQuest(player, mysticQuestTracked).orElse(null);
    }

    /**
     * Every mirrored quest the player tracks in its own mod, for the rows under MysticQuests' tracker;
     * empty when that tracker shows MysticQuests quests only.
     */
    public List<ExternalQuest> trackedQuests(UUID player) {
        if (!Boolean.TRUE.equals(config.unifiedTracker())) {
            return List.of();
        }
        return book.tracked(player);
    }

    public void forget(UUID player) {
        nextSync.remove(player);
        book.forget(player);
    }

    public void close() {
        for (ExternalQuestBridge bridge : bridges) {
            try {
                bridge.close();
            } catch (RuntimeException | LinkageError failure) {
                logger.at(Level.FINE).withCause(failure).log("Could not close the " + bridge.displayName() + " bridge.");
            }
        }
        bridges.clear();
        if (registry != null) {
            registeredConditions.forEach(registry::unregisterCondition);
            registeredEvents.forEach(registry::unregisterEvent);
        }
        registeredConditions.clear();
        registeredEvents.clear();
        hudFilter.close();
        nextSync.clear();
        book.clear();
    }
}
