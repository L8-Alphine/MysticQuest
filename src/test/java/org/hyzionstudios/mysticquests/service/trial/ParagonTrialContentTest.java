package org.hyzionstudios.mysticquests.service.trial;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.trial.TrialResult;
import org.hyzionstudios.mysticquests.api.trial.TrialStatus;
import org.hyzionstudios.mysticquests.content.BundledPackages;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.model.ConditionDefinition;
import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.ScopedStateService;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.storage.JsonQuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Path;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * P-47 / P-48 (MysticRPG PO addendum 2026-10-24): the bundled {@code mysticrpg_paragon_trials} package
 * declares the ids MysticRPG consumes, is marked provisional (sign-off PENDING), gates on MysticRPG's
 * requirements, pays no rewards, and refuses to start when MysticRPG cannot vouch (fail closed).
 */
final class ParagonTrialContentTest {
    private static final ObjectMapper MAPPER = Json.createMapper();

    /** Trial id to the Paragon floor its start condition requires (U-073), 0 for the recovery trial. */
    private static final Map<String, Integer> TRIALS = Map.of(
            "mysticrpg:paragon_era_2", 100,
            "mysticrpg:paragon_era_3", 1000,
            "mysticrpg:paragon_era_4", 3000,
            "mysticrpg:paragon_era_5", 6000,
            "mysticrpg:paragon_recovery_trial", 0);

    @TempDir
    Path root;

    private LoadedContent load() throws IOException {
        Path packages = root.resolve("packages");
        BundledPackages.install(packages, root.resolve("data/bundled-packages.json"), MAPPER);
        return new QuestContentLoader(MAPPER).load(packages);
    }

    @Test
    @DisplayName("five provisional Paragon trials ship under the ids MysticRPG asks for")
    void contentShips() throws IOException {
        LoadedContent content = load();
        assertEquals("0.1.0", content.packageMetadata().get("mysticrpg_paragon_trials").version());
        QuestTrialService trials = new QuestTrialService(TrialQuests.of(null, () -> content, null), null, MAPPER, null,
                java.time.Clock.systemUTC(), message -> { });
        for (Map.Entry<String, Integer> trial : TRIALS.entrySet()) {
            String questId = trials.questFor(trial.getKey()).orElseThrow(() -> new AssertionError(trial.getKey()));
            assertTrue(questId.startsWith("mysticrpg_paragon_trials:"), questId);
            QuestDefinition quest = content.quests().get(questId);
            assertEquals("PENDING", quest.trial().signOff(), questId + " is provisional content");
            assertEquals("PENDING", trials.signOff(trial.getKey()).orElseThrow());
            assertTrue(quest.rewards().isEmpty(), questId + " pays nothing: a retried trial would pay twice");
            assertTrue(quest.trial().timeLimitSeconds() > 0 && quest.trial().maxDeaths() >= 0, questId);
            assertFalse(quest.objectives().isEmpty(), questId);
            ConditionDefinition gate = quest.startConditions().getFirst();
            assertEquals("requirement", gate.type());
            assertEquals("mysticrpg", gate.text("provider", ""));
            if (trial.getValue() > 0) {
                assertEquals("paragon", gate.text("check", ""));
                assertEquals(trial.getValue(), gate.integer("min", -1), questId + " opens at its era floor");
            } else {
                assertEquals("paragonDormant", gate.text("check", ""));
            }
        }
        assertTrue(trials.signOff("mysticrpg:trial/warrior").orElseThrow().isEmpty(), "class trials state none");
    }

    @Test
    @DisplayName("without MysticRPG to vouch for the player, an era trial is refused (fail closed)")
    void failsClosedWithoutRequirements() throws IOException {
        LoadedContent content = load();
        MysticQuestsEventBus bus = new MysticQuestsEventBus(null);
        PlayerSessionService sessions = new PlayerSessionService(null);
        QuestNotificationService notifications = new QuestNotificationService(sessions, null);
        PlayerQuestService quests = new PlayerQuestService(() -> content, new JsonQuestStorage(root.resolve("data/players"),
                MAPPER), new ScopedStateService(new MysticStateStore(bus), null, false, false), null, null, null,
                notifications, sessions, null, null);
        quests.bindLifecycleEvents(bus);
        QuestTrialService trials = new QuestTrialService(TrialQuests.of(quests, () -> content, notifications), null,
                MAPPER, bus, java.time.Clock.systemUTC(), message -> { });
        trials.start();
        TrialResult refused = trials.start(UUID.randomUUID(), "mysticrpg:paragon_era_2").join();
        assertEquals(TrialStatus.REFUSED, refused.status());
        assertNotNull(refused.reason());
        trials.close();
    }
}
