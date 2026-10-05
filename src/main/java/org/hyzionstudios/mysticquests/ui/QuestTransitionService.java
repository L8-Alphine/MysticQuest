package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.ui.QuestTransitions.Snapshot;
import org.hyzionstudios.mysticquests.ui.QuestTransitions.Transition;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.NotificationStyle;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.util.NotificationUtil;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.logging.Level;

/**
 * Announces quest accepted, next step, progress and complete as native notifications, each with its
 * own wording and style (Redesign Bible §6.8).
 *
 * <p>Every announcement carries a stable per-quest tag, and the client replaces a same-tag toast in
 * place ({@code NotificationUtil#sendNotification}'s {@code tag}): ten kills are one live counter,
 * not ten toasts, and "complete" replaces a still-visible "accepted".
 *
 * <p>A player's first snapshot — taken when they join, and again after a content reload — is a
 * baseline and announces nothing, so a reconnect never replays a completion. Off unless
 * {@code ui.transitionCards} is set: content that sends its own {@code notification} events at these
 * moments would otherwise show both.
 */
public final class QuestTransitionService {
    private final PlayerQuestService questService;
    private final PlayerSessionService sessionService;
    private final HytaleLogger logger;
    private final boolean enabled;
    private final Map<UUID, Snapshot> baselines = new ConcurrentHashMap<>();

    public QuestTransitionService(
            PlayerQuestService questService,
            PlayerSessionService sessionService,
            boolean enabled,
            HytaleLogger logger) {
        this.questService = questService;
        this.sessionService = sessionService;
        this.enabled = enabled;
        this.logger = logger;
    }

    /** Records where the player stands without announcing anything. */
    public void seed(UUID playerId) {
        if (enabled) {
            baselines.put(playerId, snapshot(playerId));
        }
    }

    /** After a reload quest shapes may differ; start every online player from a fresh baseline. */
    public void reseedAll() {
        for (UUID playerId : sessionService.onlinePlayerIds()) {
            seed(playerId);
        }
    }

    public void forget(UUID playerId) {
        baselines.remove(playerId);
    }

    /** Players who turned quest pop-ups off; their baseline still advances, so turning them back on never replays. */
    private volatile java.util.function.Predicate<UUID> muted = player -> false;

    public void bindMute(java.util.function.Predicate<UUID> muted) {
        this.muted = muted;
    }

    /** Whether the server shows quest update pop-ups at all ({@code ui.transitionCards}). */
    public boolean enabled() {
        return enabled;
    }

    /** Quest change listener: diff against the last snapshot and announce what moved. */
    public void onQuestStateChanged(UUID playerId) {
        if (!enabled) {
            return;
        }
        PlayerRef playerRef = sessionService.playerRef(playerId);
        if (playerRef == null) {
            // Staff changed an offline player's quests; they rejoin to a fresh baseline.
            baselines.remove(playerId);
            return;
        }
        List<Transition> transitions = new ArrayList<>();
        // compute() serialises changes for one player, so two saves racing each other can never be
        // diffed in reverse order.
        baselines.compute(playerId, (id, before) -> {
            Snapshot after = snapshot(id);
            if (before != null) {
                transitions.addAll(QuestTransitions.between(before, after));
            }
            return after;
        });
        if (muted.test(playerId)) {
            return;
        }
        for (Transition transition : transitions) {
            send(playerRef, transition);
        }
    }

    private Snapshot snapshot(UUID playerId) {
        return Snapshot.of(questService.journal(playerId), questService.completedRecords(playerId));
    }

    private void send(PlayerRef playerRef, Transition transition) {
        Message title;
        Message detail;
        NotificationStyle style = NotificationStyle.Default;
        switch (transition.kind()) {
            case ACCEPTED -> {
                title = Message.raw("Quest accepted: " + transition.questTitle());
                detail = Message.raw(transition.detail());
            }
            case STEP -> {
                title = Message.raw(transition.questTitle());
                detail = Message.raw("New step  |  " + transition.detail());
            }
            case PROGRESS -> {
                title = Message.raw(transition.questTitle());
                detail = Message.raw(transition.detail());
            }
            case COMPLETED -> {
                title = Message.raw("Quest complete");
                detail = Message.raw(transition.questTitle());
                style = NotificationStyle.Success;
            }
            default -> {
                return;
            }
        }
        try {
            NotificationUtil.sendNotification(
                    playerRef.getPacketHandler(), title, detail, null, null, style, transition.tag());
        } catch (RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log(
                    "Failed to send MysticQuests " + transition.kind() + " notification to " + playerRef.getUuid() + ".");
        }
    }
}
