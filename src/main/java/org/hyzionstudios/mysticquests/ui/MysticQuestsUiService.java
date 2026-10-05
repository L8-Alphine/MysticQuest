package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.MysticQuestsRuntime;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;

import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.command.system.CommandContext;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import java.util.logging.Level;

public final class MysticQuestsUiService {
    private final MysticQuestsRuntime runtime;
    private final PlayerQuestService questService;
    private final HytaleLogger logger;

    public MysticQuestsUiService(MysticQuestsRuntime runtime, HytaleLogger logger) {
        this.runtime = runtime;
        this.questService = runtime.questService();
        this.logger = logger;
    }

    public boolean openJournal(CommandContext context) {
        return open(context, true);
    }

    public boolean openQuestMenu(CommandContext context) {
        return open(context, false);
    }

    public boolean openAdminStudio(CommandContext context) {
        if (!context.isPlayer() || !shipped(UiDocuments.QUEST_STUDIO)) {
            return false;
        }
        try {
            Ref<EntityStore> playerEntity = context.senderAsPlayerRef();
            Store<EntityStore> store = playerEntity.getStore();
            Player player = store.getComponent(playerEntity, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(playerEntity, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return false;
            }
            player.getPageManager().openCustomPage(
                    playerEntity,
                    store,
                    new QuestStudioPage(playerRef, runtime));
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to open MysticQuests quest studio UI.");
            return false;
        }
    }

    /** Refuses to append a document this build does not ship; the append would kick the player. */
    private boolean shipped(String document) {
        if (UiDocuments.isShipped(document)) {
            return true;
        }
        logger.at(Level.WARNING).log("MysticQuests UI unavailable: this build does not ship " + document + ".");
        return false;
    }

    private boolean open(CommandContext context, boolean journal) {
        if (!context.isPlayer()
                || !shipped(journal ? UiDocuments.JOURNAL : UiDocuments.QUEST_MENU)) {
            return false;
        }
        try {
            Ref<EntityStore> playerEntity = context.senderAsPlayerRef();
            Store<EntityStore> store = playerEntity.getStore();
            Player player = store.getComponent(playerEntity, Player.getComponentType());
            PlayerRef playerRef = store.getComponent(playerEntity, PlayerRef.getComponentType());
            if (player == null || playerRef == null) {
                return false;
            }
            player.getPageManager().openCustomPage(
                    playerEntity,
                    store,
                    journal
                            ? new MysticQuestJournalPage(playerRef, playerRef.getUuid(), questService, new RuntimeJournalSources(runtime))
                            : new QuestMenuPage(playerRef, playerRef.getUuid(), questService));
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to open MysticQuests " + (journal ? "journal" : "quest menu") + " UI.");
            return false;
        }
    }
}
