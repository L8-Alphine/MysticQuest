package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.api.QuestPartyProvider;

import java.util.UUID;

public final class QuestSignalBus {
    private final PlayerQuestService questService;
    private final QuestPartyProvider parties;

    public QuestSignalBus(PlayerQuestService questService) {
        this(questService, actor -> java.util.Set.of(actor));
    }

    public QuestSignalBus(PlayerQuestService questService, QuestPartyProvider parties) {
        this.questService = questService;
        this.parties = parties;
    }

    public void publish(QuestSignal signal) {
        questService.handleSignal(signal);
        for (UUID member : parties.members(signal.playerId())) {
            if (!member.equals(signal.playerId())) {
                questService.handleSharedSignal(new QuestSignal(
                        member, signal.type(), signal.target(), signal.amount(), signal.world(),
                        signal.x(), signal.y(), signal.z(), signal.targetContext()));
            }
        }
    }
}
