package org.hyzionstudios.mysticquests;

import org.hyzionstudios.mysticquests.command.MQuestCommand;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.hytale.HytaleEventBridge;
import org.hyzionstudios.mysticquests.integration.HyCitizensBridge;
import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.integration.PlaceholderIntegration;
import org.hyzionstudios.mysticquests.integration.MysticPartyIntegration;
import org.hyzionstudios.mysticquests.integration.VaultUnlockedEconomyBridge;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerInventoryService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.PlayerVisibilityService;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
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
    private ScopedStateService scopedStateService;
    private QuestPacketService packetService;
    private PlaceholderIntegration placeholderIntegration;
    private MysticQuestsUiService uiService;
    private PlayerSessionService sessionService;
    private QuestNotificationService notificationService;
    private QuestHudService hudService;
    private ConversationService conversationService;
    private HyCitizensBridge hyCitizensBridge;
    private QuestAuthoringService authoringService;
    private QuestScheduleService scheduleService;
    private MysticPartyIntegration partyIntegration;
    private PlayerVisibilityService visibilityService;

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
            this.packetService = new QuestPacketService(plugin.getLogger());
            this.sessionService = new PlayerSessionService(plugin.getLogger());
            this.notificationService = new QuestNotificationService(sessionService, plugin.getLogger());
            PlayerInventoryService inventoryService =
                    new PlayerInventoryService(sessionService, notificationService, plugin.getLogger());
            HyExtrasBridge hyExtrasBridge = new HyExtrasBridge(
                    config.integrations().hyExtras(),
                    Boolean.TRUE.equals(config.integrations().hyExtrasExportPlayerState()),
                    plugin.getLogger());
            this.scopedStateService = new ScopedStateService(
                    storage,
                    hyExtrasBridge,
                    config.state().migrateLegacyPlayerTags(),
                    config.state().migrateLegacyPlayerVariables(),
                    plugin.getLogger());
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
            this.visibilityService = new PlayerVisibilityService(content::get, questService, sessionService, plugin.getLogger());
            this.questService.addChangeListener(ignored -> visibilityService.reconcileAll());
            hyExtrasBridge.registerMysticQuestTriggers(scopedStateService, questService);
            this.conversationService = new ConversationService(content::get, questService, signalBus, plugin.getLogger());
            this.questService.bindConversationSupport(
                    conversationService::isInConversation,
                    conversationService::cancel);
            ConversationService.activatePageSupplier(this.conversationService);
            this.hyCitizensBridge = new HyCitizensBridge(config.integrations().hyCitizens(), conversationService, plugin.getLogger());
            this.hyCitizensBridge.register();
            this.authoringService = new QuestAuthoringService(
                    dataDirectory.resolve(config.packagesPath()), mapper, content::get, this::reloadContent);
            this.uiService = new MysticQuestsUiService(this, plugin.getLogger());
            reloadContent();
            this.scheduleService.start();
            new HytaleEventBridge(this, plugin, signalBus, questService, conversationService, hudService, sessionService).register();
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "journal", "journal"));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "quest", "menu"));
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this, "quests", "menu"));
            this.placeholderIntegration = new PlaceholderIntegration(config.integrations().placeholderApi(), questService);
            this.placeholderIntegration.register();
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
        content.set(loaded);
        if (packetService != null) {
            packetService.clearAll();
        }
        if (hudService != null) {
            hudService.reconcileAll();
        }
        if (conversationService != null) {
            conversationService.reconcileInteractablesAll();
        }
        if (visibilityService != null) {
            visibilityService.reconcileAll();
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

    public QuestHudService hudService() {
        return hudService;
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
        if (visibilityService != null) {
            visibilityService.reconcileAll();
        }
    }

    public void unregisterOnlinePlayer(java.util.UUID playerId) {
        String previous = onlinePlayerNamesById.remove(playerId);
        if (previous != null) {
            onlinePlayersByName.remove(previous.toLowerCase(Locale.ROOT));
        }
        if (visibilityService != null) {
            visibilityService.reconcileAll();
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
        if (scheduleService != null) {
            scheduleService.close();
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
        if (conversationService != null) {
            ConversationService.deactivatePageSupplier(conversationService);
        }
        if (packetService != null) {
            packetService.clearAll();
        }
        if (hudService != null) {
            hudService.clear();
            hudService.close();
        }
        if (sessionService != null) {
            sessionService.clear();
        }
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
