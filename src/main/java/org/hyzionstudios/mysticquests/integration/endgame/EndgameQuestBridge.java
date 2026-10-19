package org.hyzionstudios.mysticquests.integration.endgame;

import org.hyzionstudios.mysticquests.external.ExternalObjective;
import org.hyzionstudios.mysticquests.external.ExternalQuest;
import org.hyzionstudios.mysticquests.external.ExternalQuestSnapshot;
import org.hyzionstudios.mysticquests.external.ExternalSignals;
import org.hyzionstudios.mysticquests.external.TitleCache;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.registry.Registration;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import endgame.plugin.EndgameQoL;
import endgame.plugin.config.BountyData;
import endgame.plugin.events.domain.GameEvent;
import endgame.plugin.events.domain.GameEventBus;
import endgame.plugin.quest.PlayerQuestState;
import endgame.plugin.quest.QuestCatalog;
import endgame.plugin.quest.QuestDefinition;
import endgame.plugin.quest.QuestService;
import endgame.plugin.ui.NativeJournalPage;
import endgame.plugin.ui.QuestTrackerHud;
import endgame.plugin.utils.I18n;

import javax.annotation.Nullable;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mirrors Endgame &amp; QoL's quests: its story and side quests ({@code Server/Endgame/Quests/Story.json},
 * {@code Side.json}) from the player's {@link PlayerQuestState}, and the contracts they accepted from
 * the contract board. Endgame keeps running them — its quest givers, its trackers for boss kills,
 * trials, combo tiers and dungeons, and its rewards — and MysticQuests reads the result.
 *
 * <p>Words come from Endgame's own translations through {@link QuestService#text} and
 * {@link QuestService#objectiveText}, the calls its tracker HUD makes, so a mirrored quest reads the
 * same as in Endgame's journal.
 *
 * <p>Signals forwarded from Endgame's {@link GameEventBus}:
 * <pre>
 *   endgame:boss_kill:&lt;boss npc id&gt;     endgame:boss_kill        every credited player
 *   endgame:dungeon_enter:&lt;dungeon&gt;     endgame:dungeon_enter
 *   endgame:achievement:&lt;id&gt;            endgame:achievement
 *   endgame:combo_tier:&lt;tier&gt;           endgame:combo_tier       when a combo climbs to that tier
 * </pre>
 * Quest acceptances and completions come from the snapshot diff ({@code endgame:quest_completed:<id>}).
 */
public final class EndgameQuestBridge implements ExternalQuestBridge {
    static final String ID = "endgame";
    static final String CATEGORY = "endgame";
    /** Contracts share the quest catalog with story quests; this keeps their keys apart. */
    static final String CONTRACT_PREFIX = "contract/";

    private final HytaleLogger logger;
    private final List<Registration> subscriptions = new CopyOnWriteArrayList<>();
    private final TitleCache titles = new TitleCache();

    public EndgameQuestBridge(HytaleLogger logger) {
        this.logger = logger;
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String category() {
        return CATEGORY;
    }

    @Override
    public String displayName() {
        return "Endgame & QoL";
    }

    @Override
    public Set<String> trackerHudKeys() {
        return Set.of(QuestTrackerHud.KEY);
    }

    @Nullable
    private static QuestService quests() {
        EndgameQoL plugin = EndgameQoL.getInstance();
        return plugin == null ? null : plugin.getQuestService();
    }

    @Override
    public ExternalQuestSnapshot snapshot(PlayerHandle player) {
        QuestService quests = quests();
        UUID id = player.id();
        PlayerQuestState state = quests == null ? null : quests.questState(id);
        if (state == null) {
            return ExternalQuestSnapshot.empty(ID);
        }
        PlayerRef playerRef = player.playerRef();
        QuestCatalog catalog = quests.catalog();
        List<ExternalQuest> active = new ArrayList<>();
        for (Map.Entry<String, PlayerQuestState.ActiveQuest> entry : state.active().entrySet()) {
            QuestDefinition definition = catalog.get(entry.getKey());
            if (definition != null) {
                active.add(quest(quests, playerRef, definition, entry.getValue()));
            }
        }
        active.addAll(contracts(quests, playerRef, catalog, id));
        Map<String, Long> completed = Map.copyOf(state.completed());
        return new ExternalQuestSnapshot(ID, active, List.copyOf(state.tracked()), completed.keySet(),
                completions(playerRef, catalog, completed), Map.of());
    }

    /**
     * Finished quests' titles in the player's language, and when Endgame says each was finished. A
     * title is worded once per language: this runs every second for every player.
     */
    private Map<String, ExternalQuestSnapshot.Completion> completions(PlayerRef playerRef, QuestCatalog catalog,
                                                                      Map<String, Long> completed) {
        // Endgame words text in its own locale for the player, which a player can set apart from the game's.
        String locale = I18n.resolveLocale(playerRef);
        Map<String, ExternalQuestSnapshot.Completion> completions = new HashMap<>();
        completed.forEach((questId, finishedAt) -> {
            String title = titles.title(catalog, locale, questId, key -> {
                QuestDefinition definition = catalog.get(key);
                return definition == null ? "" : QuestService.text(playerRef, definition.title());
            });
            // Endgame stamps a completion with System.currentTimeMillis(); anything else is not a time.
            Instant at = finishedAt != null && finishedAt > 0 ? Instant.ofEpochMilli(finishedAt) : null;
            completions.put(questId, new ExternalQuestSnapshot.Completion(title, at));
        });
        return completions;
    }

    private static ExternalQuest quest(QuestService quests, PlayerRef playerRef, QuestDefinition definition,
                                       PlayerQuestState.ActiveQuest progress) {
        List<QuestDefinition.Stage> stages = definition.stages();
        int stageIndex = Math.max(0, Math.min(progress.stage(), stages.isEmpty() ? 0 : stages.size() - 1));
        List<ExternalObjective> objectives = new ArrayList<>();
        if (!stages.isEmpty()) {
            List<QuestDefinition.Objective> current = stages.get(stageIndex).objectives();
            for (int index = 0; index < current.size(); index++) {
                QuestDefinition.Objective objective = current.get(index);
                objectives.add(ExternalObjective.of(objective.type().jsonName() + "_" + index,
                        QuestService.objectiveText(playerRef, definition, objective),
                        progress.progress(index), Math.max(1, objective.count())));
            }
        }
        boolean ready = QuestService.isReadyToHandIn(definition, progress);
        String nextAction = "";
        if (ready) {
            String turnIn = definition.turnInNpc();
            String name = turnIn == null || turnIn.isBlank() ? "" : quests.npcName(playerRef, turnIn);
            nextAction = name == null || name.isBlank() ? "Hand it in" : "Return to " + name;
        }
        return new ExternalQuest(ID, definition.id(),
                QuestService.text(playerRef, definition.title()),
                QuestService.text(playerRef, definition.description()),
                CATEGORY,
                stageIndex + 1, Math.max(1, stages.size()),
                definition.chapter() == null || definition.chapter().isBlank() ? "" : QuestService.text(playerRef, definition.chapter()),
                objectives,
                ready ? ExternalQuest.Status.READY : ExternalQuest.Status.ACTIVE,
                nextAction,
                rewards(definition.rewards()));
    }

    /** Accepted contracts not yet claimed, daily slots then the weekly one. */
    private static List<ExternalQuest> contracts(QuestService quests, PlayerRef playerRef, QuestCatalog catalog, UUID player) {
        BountyData.PlayerBountyState board = quests.contractBoard(player);
        if (board == null) {
            return List.of();
        }
        List<BountyData.ActiveBounty> bounties = new ArrayList<>(board.getBounties());
        if (board.getWeeklyBounty() != null) {
            bounties.add(board.getWeeklyBounty());
        }
        List<ExternalQuest> contracts = new ArrayList<>();
        for (BountyData.ActiveBounty bounty : bounties) {
            if (bounty == null || !bounty.isAccepted() || bounty.isClaimed()) {
                continue;
            }
            QuestDefinition definition = catalog.get(bounty.getTemplateId());
            if (definition == null) {
                continue;
            }
            String text = QuestService.contractText(playerRef, definition);
            boolean done = bounty.isCompleted();
            contracts.add(new ExternalQuest(ID, CONTRACT_PREFIX + definition.id(),
                    text, "", CATEGORY, 1, 1,
                    definition.tier() == null ? "Contract" : "Contract  |  " + sentence(definition.tier().name()),
                    List.of(ExternalObjective.of("contract", text, bounty.getProgress(), Math.max(1, bounty.getTarget()))),
                    done ? ExternalQuest.Status.READY : ExternalQuest.Status.ACTIVE,
                    done ? "Claim it at the contract board" : "",
                    rewards(definition.rewards())));
        }
        return contracts;
    }

    /** "150 XP, 3 Bar Adamantite": what the quest announces, items by a readable form of their id. */
    static String rewards(@Nullable QuestDefinition.Rewards rewards) {
        if (rewards == null) {
            return "";
        }
        List<String> parts = new ArrayList<>();
        if (rewards.xp() > 0) {
            parts.add(rewards.xp() + " XP");
        }
        for (QuestDefinition.ItemReward item : rewards.items()) {
            parts.add(item.count() + " " + itemName(item.itemId()));
        }
        if (rewards.reputation() > 0) {
            parts.add(rewards.reputation() + " reputation");
        }
        return String.join(", ", parts);
    }

    /** {@code Ingredient_Bar_Adamantite} reads as "Bar Adamantite"; the raw id when it has no parts. */
    static String itemName(String itemId) {
        if (itemId == null || itemId.isBlank()) {
            return "";
        }
        String name = itemId.replaceFirst("^(Ingredient|Endgame|Item)_", "").replace('_', ' ').strip();
        return name.isEmpty() ? itemId : name;
    }

    private static String sentence(String word) {
        String lower = word.toLowerCase(Locale.ROOT);
        return lower.isEmpty() ? lower : Character.toUpperCase(lower.charAt(0)) + lower.substring(1);
    }

    @Override
    public boolean track(PlayerHandle player, String questId) {
        QuestService quests = quests();
        if (quests == null || questId.startsWith(CONTRACT_PREFIX)) {
            return false;
        }
        quests.track(player.id(), questId);
        return true;
    }

    @Override
    public boolean untrack(PlayerHandle player, String questId) {
        QuestService quests = quests();
        if (quests == null || questId.startsWith(CONTRACT_PREFIX)) {
            return false;
        }
        quests.untrack(player.id(), questId);
        return true;
    }

    /** Endgame refuses to drop story quests; side quests may be abandoned, contracts are not quests here. */
    @Override
    public boolean abandon(PlayerHandle player, String questId) {
        QuestService quests = quests();
        return quests != null && !questId.startsWith(CONTRACT_PREFIX) && quests.abandon(player.id(), questId);
    }

    /** As if the quest's giver had offered it: Endgame only accepts a quest from its own giver. */
    @Override
    public boolean accept(PlayerHandle player, String questId) {
        QuestService quests = quests();
        if (quests == null) {
            return false;
        }
        QuestDefinition definition = quests.catalog().get(questId);
        if (definition == null || definition.isContract()) {
            return false;
        }
        return quests.accept(player.id(), questId, definition.giver()) == QuestService.AcceptResult.ACCEPTED;
    }

    @Override
    public String bookLabel() {
        return "Endgame Quests";
    }

    /** Endgame's journal on its Quests tab: the page its journal hotkey, journal book and {@code /eg journal} open. */
    @Override
    public boolean openBook(PlayerHandle player) {
        EndgameQoL plugin = EndgameQoL.getInstance();
        if (plugin == null) {
            return false;
        }
        NativeJournalPage.open(plugin, player.playerRef(), player.store(), player.id(), "Quests");
        return true;
    }

    @Override
    public void forwardSignals(SignalSink signals) {
        EndgameQoL plugin = EndgameQoL.getInstance();
        GameEventBus bus = plugin == null ? null : plugin.getGameEventBus();
        if (bus == null) {
            logger.at(java.util.logging.Level.INFO).log("Endgame & QoL has no event bus yet; boss kills will not be forwarded.");
            return;
        }
        subscriptions.add(bus.subscribe(GameEvent.BossKillEvent.class, event -> {
            Set<UUID> credited = new LinkedHashSet<>(event.creditedPlayers());
            if (event.killerUuid() != null) {
                credited.add(event.killerUuid());
            }
            for (UUID player : credited) {
                publish(signals, player, "endgame:boss_kill", event.bossTypeId());
            }
        }));
        subscriptions.add(bus.subscribe(GameEvent.DungeonEnterEvent.class,
                event -> publish(signals, event.playerUuid(), "endgame:dungeon_enter", event.dungeonType())));
        subscriptions.add(bus.subscribe(GameEvent.AchievementUnlockEvent.class,
                event -> publish(signals, event.playerUuid(), "endgame:achievement", event.achievementId())));
        subscriptions.add(bus.subscribe(GameEvent.ComboTierChangeEvent.class, event -> {
            if (event.newTier() > event.oldTier()) {
                publish(signals, event.playerUuid(), "endgame:combo_tier", Integer.toString(event.newTier()));
            }
        }));
    }

    private static void publish(SignalSink signals, UUID player, String moment, String subject) {
        if (player == null) {
            return;
        }
        List<ExternalSignals.Signal> pair = new ArrayList<>();
        ExternalSignals.pair(pair, moment, subject == null ? "" : subject, 1);
        for (ExternalSignals.Signal signal : pair) {
            signals.publish(player, signal.name(), signal.amount());
        }
    }

    @Override
    public Map<String, Integer> counts() {
        QuestService quests = quests();
        if (quests == null) {
            return Map.of();
        }
        QuestCatalog catalog = quests.catalog();
        return Map.of("story quests", catalog.byCategory(QuestDefinition.Category.STORY).size(),
                "side quests", catalog.byCategory(QuestDefinition.Category.SIDE).size(),
                "contracts", catalog.byCategory(QuestDefinition.Category.CONTRACT).size());
    }

    @Override
    public void close() {
        for (Registration subscription : subscriptions) {
            try {
                subscription.unregister();
            } catch (RuntimeException ignored) {
                // Endgame may already have torn its bus down.
            }
        }
        subscriptions.clear();
    }
}
