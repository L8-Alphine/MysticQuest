package org.hyzionstudios.mysticquests;

import org.hyzionstudios.mysticquests.command.MQuestCommand;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.hytale.HytaleEventBridge;
import org.hyzionstudios.mysticquests.state.EntityIndexService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.state.StateWriteQueue;
import org.hyzionstudios.mysticquests.integration.HyCitizensBridge;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticVanishBridge;
import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.integration.PlaceholderIntegration;
import org.hyzionstudios.mysticquests.integration.MysticPartyIntegration;
import org.hyzionstudios.mysticquests.integration.VaultUnlockedEconomyBridge;
import org.hyzionstudios.mysticquests.integration.narrative.NarrativeIntegration;
import org.hyzionstudios.mysticquests.integration.triggervolumes.MysticTriggerVolumeRegistrar;
import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.api.MysticQuestsApi;
import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.service.TargetingPreventionService;
import org.hyzionstudios.mysticquests.service.VisibilityService;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerInventoryService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.PlayerVisibilityService;
import org.hyzionstudios.mysticquests.service.QuestActionServices;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
import org.hyzionstudios.mysticquests.service.TargetSelector;
import org.hyzionstudios.mysticquests.service.QuestAuthoringService;
import org.hyzionstudios.mysticquests.service.QuestScheduleService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.storage.JsonQuestStorage;
import org.hyzionstudios.mysticquests.storage.QuestStorage;
import org.hyzionstudios.mysticquests.storage.SqliteQuestStorage;
import org.hyzionstudios.mysticquests.ui.MysticQuestsUiService;
import org.hyzionstudios.mysticquests.ui.QuestHudService;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Collection;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

public final class MysticQuestsRuntime implements AutoCloseable {
    /** Folder name under the server's mods directory: {@code mods/MysticQuests}. */
    private static final String DATA_DIRECTORY_NAME = "MysticQuests";

    private final JavaPlugin plugin;
    private Path dataDirectory;
    private final ObjectMapper mapper;
    private final AtomicReference<LoadedContent> content;
    private final Map<String, java.util.UUID> onlinePlayersByName = new ConcurrentHashMap<>();
    private final Map<java.util.UUID, String> onlinePlayerNamesById = new ConcurrentHashMap<>();
    private MysticQuestsConfig config;
    private QuestStorage storage;
    private PlayerQuestService questService;
    private QuestSignalBus signalBus;
    private MysticQuestsEventBus eventBus;
    private MysticStateStore stateStore;
    private StateWriteQueue stateWriteQueue;
    private final EntityIndexService entityIndex = new EntityIndexService();
    private ScopedStateService scopedStateService;
    private QuestPacketService packetService;
    private PlaceholderIntegration placeholderIntegration;
    private MysticQuestsUiService uiService;
    private PlayerSessionService sessionService;
    private QuestNotificationService notificationService;
    private QuestHudService hudService;
    private ConversationService conversationService;
    private HyCitizensBridge hyCitizensBridge;
    private MysticGenerationBridge generationBridge;
    private QuestAuthoringService authoringService;
    private QuestScheduleService scheduleService;
    private MysticPartyIntegration partyIntegration;
    private PlayerVisibilityService visibilityService;
    private VisibilityService visibility;
    private MysticVanishBridge vanishBridge;
    private TargetingPreventionService targeting;
    private MysticQuestsRegistry registry;
    private NarrativeIntegration narrative;
    /**
     * False only during the first content load in {@link #start()}. That load tolerates narrative
     * errors; every reload after it is transactional across both runtimes.
     */
    private boolean initialLoadDone;

    public MysticQuestsRuntime(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mapper = Json.createMapper();
        this.content = new AtomicReference<>(LoadedContent.empty());
    }

    public void start() {
        try {
            this.dataDirectory = resolveDataDirectory();
            Files.createDirectories(dataDirectory);
            plugin.getLogger().at(Level.INFO)
                    .log("MysticQuests data directory: " + dataDirectory.toAbsolutePath());
            warnAboutLegacyDataDirectory();
            this.config = MysticQuestsConfig.load(dataDirectory, mapper);
            this.storage = openStorage(config);
            this.sessionService = new PlayerSessionService(plugin.getLogger());
            this.packetService = new QuestPacketService(sessionService, plugin.getLogger());
            this.notificationService = new QuestNotificationService(sessionService, plugin.getLogger());
            PlayerInventoryService inventoryService =
                    new PlayerInventoryService(sessionService, notificationService, plugin.getLogger());
            HyExtrasBridge hyExtrasBridge = new HyExtrasBridge(
                    config.integrations().hyExtras(),
                    Boolean.TRUE.equals(config.integrations().hyExtrasExportPlayerState()),
                    plugin.getLogger());
            this.eventBus = new MysticQuestsEventBus(plugin.getLogger());
            this.stateStore = new MysticStateStore(eventBus);
            this.stateStore.load(storage.loadState());
            this.stateWriteQueue = new StateWriteQueue(
                    stateStore, storage, config.state().saveIntervalMillis(), plugin.getLogger());
            this.scopedStateService = new ScopedStateService(
                    stateStore,
                    hyExtrasBridge,
                    config.state().migrateLegacyPlayerTags(),
                    config.state().migrateLegacyPlayerVariables());
            this.questService = new PlayerQuestService(
                    content::get,
                    storage,
                    scopedStateService,
                    packetService,
                    new VaultUnlockedEconomyBridge(config.integrations().vaultUnlocked()),
                    hyExtrasBridge,
                    notificationService,
                    sessionService,
                    inventoryService,
                    plugin.getLogger());
            this.partyIntegration = new MysticPartyIntegration(plugin.getLogger());
            this.partyIntegration.register();
            this.questService.bindPartySupport(partyIntegration::members);
            this.vanishBridge = new MysticVanishBridge(
                    Boolean.TRUE.equals(config.integrations().mysticVanish()), plugin.getLogger());
            this.visibility =
                    new VisibilityService(sessionService, eventBus, plugin.getLogger(), vanishBridge);
            this.targeting = new TargetingPreventionService(sessionService, eventBus);
            this.registry = new MysticQuestsRegistry(plugin.getLogger());
            // Built before the action services because the target selector and the quest actions both
            // take it; it binds lazily, so MysticGeneration starting later than MysticQuests is fine.
            this.generationBridge = new MysticGenerationBridge(
                    config.integrations().mysticGeneration(), plugin.getLogger());
            this.generationBridge.register();
            this.questService.bindActionServices(new QuestActionServices(
                    visibility,
                    targeting,
                    new TargetSelector(
                            stateStore, entityIndex, sessionService, partyIntegration::members, generationBridge),
                    registry,
                    generationBridge));
            this.signalBus = new QuestSignalBus(questService, partyIntegration);
            this.scheduleService = new QuestScheduleService(
                    content::get, questService, sessionService, plugin.getLogger(),
                    dataDirectory.resolve("data/schedule-state.json"));
            this.hudService = new QuestHudService(
                    questService,
                    sessionService,
                    config.ui().questHud(),
                    config.ui().hudJoinDelayMillis(),
                    plugin.getLogger());
            this.questService.addChangeListener(hudService::reconcile);
            this.visibilityService = new PlayerVisibilityService(
                    content::get, questService, sessionService, visibility, eventBus);
            // Reconcile only the player whose quest state changed, not every pair on the server.
            this.questService.addChangeListener(visibilityService::reconcilePlayer);
            // Types were registered in setup(), before any volume could decode. This only binds the
            // services those types call into when a volume fires.
            new MysticTriggerVolumeRegistrar(plugin.getLogger()).bindServices(scopedStateService, questService);
            this.conversationService = new ConversationService(content::get, questService, signalBus, plugin.getLogger());
            this.questService.bindConversationSupport(
                    conversationService::isInConversation,
                    conversationService::cancel);
            ConversationService.activatePageSupplier(this.conversationService);
            this.conversationService.bindGenerationSupport(generationBridge);
            this.hyCitizensBridge = new HyCitizensBridge(config.integrations().hyCitizens(), conversationService, plugin.getLogger());
            this.hyCitizensBridge.register();
            this.authoringService = new QuestAuthoringService(
                    dataDirectory.resolve(config.packagesPath()), mapper, content::get, this::reloadContent);
            this.uiService = new MysticQuestsUiService(this, plugin.getLogger());
            // Before the first reload, which compiles narrative sections alongside the v1 content.
            this.narrative = new NarrativeIntegration(
                    dataDirectory, config.narrative(), mapper, partyIntegration, questService, signalBus,
                    registry, sessionService, generationBridge, plugin.getLogger());
            // Story entities (§8) and overlay barriers (§9) are presented per viewer.
            visibility.addPresentationLayer(narrative.runtime().storyEntities());
            visibility.addPresentationLayer(narrative.runtime().overlays());
            // Dialogue nodes with a "voice" play it through the narrative media system (§15).
            conversationService.bindVoice(narrative::playVoice);
            reloadContent();
            this.scheduleService.start();
            new HytaleEventBridge(
                    this, plugin, signalBus, questService, conversationService, hudService, sessionService,
                    generationBridge).register();
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this));
            // The 2.0 specification's short form: /mq visibility bypass, /mq narrative ...
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "mq", ""));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "journal", "journal"));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "quest", "menu"));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "quests", "menu"));
            this.placeholderIntegration = new PlaceholderIntegration(config.integrations().placeholderApi(), questService);
            this.placeholderIntegration.register();
            this.questService.bindExternalTextResolver((playerId, text) ->
                    placeholderIntegration.resolve(sessionService.playerRef(playerId), text));
            // Published last: other mods must never see a half-built API.
            MysticQuestsApi.install(new MysticQuestsApi(
                    stateStore, entityIndex, visibility, targeting, packetService, questService, registry, eventBus,
                    narrative.runtime()));
            plugin.getLogger().at(Level.INFO).log("MysticQuests V1 runtime started.");
        } catch (Exception exception) {
            plugin.getLogger().at(Level.SEVERE).withCause(exception).log("MysticQuests failed to start.");
            throw new IllegalStateException("MysticQuests failed to start", exception);
        }
    }

    /**
     * Resolves the mod's data root to {@code <mods dir>/MysticQuests}.
     *
     * <p>The mods directory is taken from the location of the loaded jar rather than a hardcoded
     * {@code mods} literal, so a server started with a custom {@code --mods} directory still keeps
     * quest content beside its jar. Falls back to the server-supplied data directory when the plugin
     * runs from the classpath and has no jar file.
     */
    private Path resolveDataDirectory() {
        Path jar = plugin.getFile();
        Path modsDirectory = jar == null ? null : jar.getParent();
        if (modsDirectory == null) {
            Path fallback = plugin.getDataDirectory();
            plugin.getLogger().at(Level.INFO)
                    .log("MysticQuests has no jar location (classpath plugin); using the server data directory.");
            return fallback;
        }
        return modsDirectory.resolve(DATA_DIRECTORY_NAME);
    }

    /**
     * The server may hand out a different data directory than the one we resolve. If that other
     * location already holds content, say so instead of quietly starting against an empty tree.
     */
    private void warnAboutLegacyDataDirectory() {
        Path serverDirectory = plugin.getDataDirectory();
        if (serverDirectory == null) {
            return;
        }
        try {
            if (Files.isSameFile(serverDirectory, dataDirectory)) {
                return;
            }
        } catch (IOException exception) {
            if (serverDirectory.toAbsolutePath().equals(dataDirectory.toAbsolutePath())) {
                return;
            }
        }
        if (!Files.isDirectory(serverDirectory)) {
            return;
        }
        try (var entries = Files.list(serverDirectory)) {
            if (entries.findAny().isEmpty()) {
                return;
            }
        } catch (IOException exception) {
            return;
        }
        plugin.getLogger().at(Level.WARNING).log(
                "MysticQuests found existing content at " + serverDirectory.toAbsolutePath()
                        + " but is loading from " + dataDirectory.toAbsolutePath()
                        + ". Move config.json, packages/, and data/ across to keep that content; nothing was moved automatically.");
    }

    public synchronized LoadedContent reloadContent() throws IOException {
        Path packagesPath = dataDirectory.resolve(config.packagesPath());
        QuestContentLoader loader = new QuestContentLoader(mapper);
        LoadedContent loaded = loader.load(packagesPath);
        // Compiled before anything is swapped: a narrative error fails the reload as a whole, and the
        // server keeps running the previous quests and puzzles together.
        NarrativeContent narrativeContent = null;
        if (narrative != null) {
            try {
                narrativeContent = narrative.compile(loaded);
            } catch (IOException failure) {
                if (initialLoadDone) {
                    throw failure;
                }
                // The first load runs inside start(), where another mod's narrative types may not be
                // registered yet. Failing start-up over that would take every quest down, so the
                // narrative runtime starts empty instead, the reason goes to the log, and the next
                // successful /mquest reload brings the content in.
                plugin.getLogger().at(Level.SEVERE).log(
                        "MysticQuests narrative content is disabled until /mquest reload succeeds:\n" + failure.getMessage());
                narrativeContent = NarrativeContent.empty();
            }
        }
        content.set(loaded);
        if (narrativeContent != null) {
            narrative.install(narrativeContent);
        }
        initialLoadDone = true;
        // Visibility overrides were created by rules that may no longer exist after a reload, so
        // drop them rather than leaving players invisible to each other with nothing to undo it.
        if (visibility != null) {
            visibility.clearAll();
        }
        if (hudService != null) {
            hudService.reconcileAll();
        }
        if (conversationService != null) {
            conversationService.reconcileInteractablesAll();
        }
        if (visibilityService != null) {
            // Rules themselves only change here, so this is where the dependency index is rebuilt.
            visibilityService.reloadRules();
        }
        if (scheduleService != null) {
            scheduleService.reload();
        }
        plugin.getLogger().at(Level.INFO).log("Loaded " + loaded.quests().size() + " quests from " + loaded.packages().size() + " packages.");
        return loaded;
    }

    private QuestStorage openStorage(MysticQuestsConfig config) throws IOException {
        return switch (config.storage().type().toLowerCase()) {
            case "json" -> new JsonQuestStorage(dataDirectory.resolve(config.storage().jsonPath()), mapper);
            case "sqlite" -> new SqliteQuestStorage(dataDirectory.resolve(config.storage().sqlitePath()), mapper);
            default -> throw new IOException("Unsupported storage type: " + config.storage().type());
        };
    }

    /** The mod's data root, {@code <mods>/MysticQuests}. */
    public Path dataDirectory() {
        return dataDirectory;
    }

    public ObjectMapper mapper() {
        return mapper;
    }

    public PlayerQuestService questService() {
        return questService;
    }

    public LoadedContent content() {
        return content.get();
    }

    public JavaPlugin plugin() {
        return plugin;
    }

    public MysticQuestsUiService uiService() {
        return uiService;
    }

    public ScopedStateService scopedStateService() {
        return scopedStateService;
    }

    public MysticStateStore stateStore() {
        return stateStore;
    }

    public EntityIndexService entityIndex() {
        return entityIndex;
    }

    public VisibilityService visibility() {
        return visibility;
    }

    public TargetingPreventionService targeting() {
        return targeting;
    }

    public MysticQuestsRegistry registry() {
        return registry;
    }

    public MysticQuestsEventBus eventBus() {
        return eventBus;
    }

    /** Forces pending tag and variable changes to disk; called on disconnect and on shutdown. */
    public void flushState() {
        if (stateWriteQueue != null) {
            stateWriteQueue.flush();
        }
    }

    public QuestHudService hudService() {
        return hudService;
    }

    /** The 2.0 narrative runtime; null only before {@link #start()} reaches it. */
    public NarrativeIntegration narrative() {
        return narrative;
    }

    public ConversationService conversationService() {
        return conversationService;
    }

    public PlayerSessionService sessionService() {
        return sessionService;
    }

    public HyCitizensBridge hyCitizensBridge() {
        return hyCitizensBridge;
    }

    public MysticQuestsConfig config() {
        return config;
    }

    public MysticPartyIntegration partyIntegration() {
        return partyIntegration;
    }

    public MysticVanishBridge vanishBridge() {
        return vanishBridge;
    }

    public MysticGenerationBridge generationBridge() {
        return generationBridge;
    }

    public QuestAuthoringService authoringService() {
        return authoringService;
    }

    public void registerOnlinePlayer(PlayerRef playerRef) {
        if (playerRef == null || playerRef.getUsername() == null || playerRef.getUsername().isBlank()) {
            return;
        }
        String previous = onlinePlayerNamesById.put(playerRef.getUuid(), playerRef.getUsername());
        if (previous != null) {
            onlinePlayersByName.remove(previous.toLowerCase(Locale.ROOT));
        }
        onlinePlayersByName.put(playerRef.getUsername().toLowerCase(Locale.ROOT), playerRef.getUuid());
        if (visibility != null && playerRef.hasPermission(VisibilityService.BYPASS_ALWAYS_PERMISSION)) {
            visibility.setBypassForced(playerRef.getUuid(), true);
        }
        if (visibilityService != null) {
            visibilityService.reconcilePlayer(playerRef.getUuid());
        }
    }

    public void unregisterOnlinePlayer(java.util.UUID playerId) {
        String previous = onlinePlayerNamesById.remove(playerId);
        if (previous != null) {
            onlinePlayersByName.remove(previous.toLowerCase(Locale.ROOT));
        }
        // Visibility and targeting are scene state, not save state: a departing player must not leave
        // other players permanently hidden from them, or stay untargetable on their next session.
        if (visibility != null) {
            visibility.clearPlayer(playerId);
            visibility.forgetBypass(playerId);
        }
        if (targeting != null) {
            targeting.unprotectPlayer(playerId);
        }
        if (visibilityService != null) {
            visibilityService.forgetPlayer(playerId);
        }
    }

    public Collection<String> onlinePlayerNames() {
        return onlinePlayerNamesById.values().stream().sorted(String.CASE_INSENSITIVE_ORDER).toList();
    }

    public Optional<java.util.UUID> resolveOnlinePlayer(String token) {
        if (token == null || token.isBlank()) {
            return Optional.empty();
        }
        return Optional.ofNullable(onlinePlayersByName.get(token.toLowerCase(Locale.ROOT)));
    }

    @Override
    public void close() {
        // Withdrawn first, so no other mod can call into services that are about to shut down.
        MysticQuestsApi.uninstall();
        if (scheduleService != null) {
            scheduleService.close();
        }
        // Before the party integration closes, so a final flush still sees party ids.
        if (narrative != null) {
            try {
                narrative.close();
            } catch (RuntimeException exception) {
                plugin.getLogger().at(Level.WARNING).withCause(exception).log("Failed to close the MysticQuests narrative runtime.");
            }
        }
        if (partyIntegration != null) {
            partyIntegration.close();
        }
        if (visibilityService != null) {
            visibilityService.close();
        }
        if (placeholderIntegration != null) {
            placeholderIntegration.unregister();
        }
        if (hyCitizensBridge != null) {
            hyCitizensBridge.close();
        }
        if (generationBridge != null) {
            generationBridge.close();
        }
        if (conversationService != null) {
            ConversationService.deactivatePageSupplier(conversationService);
        }
        if (visibility != null) {
            visibility.clearAll();
        }
        if (targeting != null) {
            targeting.clear();
        }
        if (registry != null) {
            registry.clear();
        }
        if (hudService != null) {
            hudService.clear();
            hudService.close();
        }
        if (sessionService != null) {
            sessionService.clear();
        }
        // Close the writer before the storage it writes through, so its final drain still lands.
        if (stateWriteQueue != null) {
            stateWriteQueue.close();
        }
        if (eventBus != null) {
            eventBus.clear();
        }
        entityIndex.clear();
        if (storage != null) {
            try {
                storage.close();
            } catch (Exception exception) {
                plugin.getLogger().at(Level.WARNING).withCause(exception).log("Failed to close MysticQuests storage.");
            }
        }
        plugin.getLogger().at(Level.INFO).log("MysticQuests V1 runtime shut down.");
    }
}
