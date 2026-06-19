package org.hyzionstudios.mysticquests;

import org.hyzionstudios.mysticquests.command.MQuestCommand;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.hytale.HytaleEventBridge;
import org.hyzionstudios.mysticquests.integration.HyExtrasBridge;
import org.hyzionstudios.mysticquests.integration.PlaceholderIntegration;
import org.hyzionstudios.mysticquests.integration.VaultUnlockedEconomyBridge;
import org.hyzionstudios.mysticquests.packet.QuestPacketService;
import org.hyzionstudios.mysticquests.service.ConversationService;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;
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

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Objects;
import java.util.concurrent.atomic.AtomicReference;
import java.util.logging.Level;

public final class MysticQuestsRuntime implements AutoCloseable {
    private final JavaPlugin plugin;
    private final ObjectMapper mapper;
    private final AtomicReference<LoadedContent> content;
    private MysticQuestsConfig config;
    private QuestStorage storage;
    private PlayerQuestService questService;
    private QuestSignalBus signalBus;
    private ScopedStateService scopedStateService;
    private QuestPacketService packetService;
    private PlaceholderIntegration placeholderIntegration;
    private MysticQuestsUiService uiService;
    private QuestNotificationService notificationService;
    private QuestHudService hudService;
    private ConversationService conversationService;

    public MysticQuestsRuntime(JavaPlugin plugin) {
        this.plugin = Objects.requireNonNull(plugin, "plugin");
        this.mapper = Json.createMapper();
        this.content = new AtomicReference<>(LoadedContent.empty());
    }

    public void start() {
        try {
            Files.createDirectories(plugin.getDataDirectory());
            this.config = MysticQuestsConfig.load(plugin.getDataDirectory(), mapper);
            this.storage = openStorage(config);
            this.packetService = new QuestPacketService(plugin.getLogger());
            this.notificationService = new QuestNotificationService(plugin.getLogger());
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
                    plugin.getLogger());
            this.signalBus = new QuestSignalBus(questService);
            this.hudService = new QuestHudService(questService, plugin.getLogger());
            this.questService.addChangeListener(hudService::reconcile);
            hyExtrasBridge.registerMysticQuestTriggers(scopedStateService, questService);
            this.conversationService = new ConversationService(content::get, questService, signalBus, plugin.getLogger());
            this.uiService = new MysticQuestsUiService(questService, plugin.getLogger());
            reloadContent();
            new HytaleEventBridge(plugin, signalBus, conversationService, hudService, notificationService).register();
            plugin.getCommandRegistry().registerCommand(new MQuestCommand(this));
            this.placeholderIntegration = new PlaceholderIntegration(config.integrations().placeholderApi(), questService);
            this.placeholderIntegration.register();
            plugin.getLogger().at(Level.INFO).log("MysticQuests V1 runtime started.");
        } catch (Exception exception) {
            plugin.getLogger().at(Level.SEVERE).withCause(exception).log("MysticQuests failed to start.");
            throw new IllegalStateException("MysticQuests failed to start", exception);
        }
    }

    public synchronized LoadedContent reloadContent() throws IOException {
        Path packagesPath = plugin.getDataDirectory().resolve(config.packagesPath());
        QuestContentLoader loader = new QuestContentLoader(mapper);
        LoadedContent loaded = loader.load(packagesPath);
        content.set(loaded);
        if (packetService != null) {
            packetService.clearAll();
        }
        if (hudService != null) {
            hudService.reconcileAll();
        }
        plugin.getLogger().at(Level.INFO).log("Loaded " + loaded.quests().size() + " quests from " + loaded.packages().size() + " packages.");
        return loaded;
    }

    private QuestStorage openStorage(MysticQuestsConfig config) throws IOException {
        Path dataDirectory = plugin.getDataDirectory();
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

    @Override
    public void close() {
        if (placeholderIntegration != null) {
            placeholderIntegration.unregister();
        }
        if (packetService != null) {
            packetService.clearAll();
        }
        if (hudService != null) {
            hudService.clear();
        }
        if (notificationService != null) {
            notificationService.clear();
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
