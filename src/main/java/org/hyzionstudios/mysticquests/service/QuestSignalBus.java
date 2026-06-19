package org.hyzionstudios.mysticquests.service;

public final class QuestSignalBus {
    private final PlayerQuestService questService;

    public QuestSignalBus(PlayerQuestService questService) {
        this.questService = questService;
    }

    public void publish(QuestSignal signal) {
        questService.handleSignal(signal);
    }
}
