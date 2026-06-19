package org.hyzionstudios.mysticquests.ui;

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
    private final PlayerQuestService questService;
    private final HytaleLogger logger;

    public MysticQuestsUiService(PlayerQuestService questService, HytaleLogger logger) {
        this.questService = questService;
        this.logger = logger;
    }

    public boolean openJournal(CommandContext context) {
        if (!context.isPlayer()) {
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
                    new MysticQuestJournalPage(playerRef, playerRef.getUuid(), questService));
            return true;
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to open MysticQuests journal UI.");
            return false;
        }
    }
}
