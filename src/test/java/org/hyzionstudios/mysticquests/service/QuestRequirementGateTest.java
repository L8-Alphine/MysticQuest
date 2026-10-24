package org.hyzionstudios.mysticquests.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.api.requirement.QuestRequirementProvider;
import org.hyzionstudios.mysticquests.api.requirement.QuestRequirementRequest;
import org.hyzionstudios.mysticquests.api.requirement.QuestRequirementResult;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.state.MysticStateStore;
import org.hyzionstudios.mysticquests.storage.JsonQuestStorage;
import org.hyzionstudios.mysticquests.ui.QuestNotificationService;
import org.hyzionstudios.mysticquests.util.Json;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.HashMap;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Quests gate on another mod's typed requirements (MysticRPG U-011, U-128): the RPG level check from a
 * registered provider opens or closes a quest's start conditions, and every failure mode denies.
 */
final class QuestRequirementGateTest {
    private static final ObjectMapper MAPPER = Json.createMapper();
    private static final UUID NOVICE = UUID.fromString("00000000-0000-0000-0000-0000000000a1");
    private static final UUID VETERAN = UUID.fromString("00000000-0000-0000-0000-0000000000a2");

    @TempDir
    Path root;

    private PlayerQuestService quests;
    private final MysticQuestsRegistry registry = new MysticQuestsRegistry(null);
    private final FakeRpg rpg = new FakeRpg();

    /** Stands in for MysticRPG's provider: levels by player, and an availability switch. */
    private static final class FakeRpg implements QuestRequirementProvider {
        final Map<UUID, Integer> levels = new HashMap<>();
        boolean available = true;

        @Override
        public String namespace() {
            return "mysticrpg";
        }

        @Override
        public boolean available() {
            return available;
        }

        @Override
        public Set<String> checks() {
            return Set.of("level");
        }

        @Override
        public QuestRequirementResult evaluate(QuestRequirementRequest request) {
            Integer level = levels.get(request.playerId());
            if (level == null) {
                return QuestRequirementResult.unverifiable("profile not loaded");
            }
            long min = request.number("min", 1);
            return level >= min ? QuestRequirementResult.met("level " + level)
                    : QuestRequirementResult.unmet("level " + level + " < " + min);
        }
    }

    @BeforeEach
    void setUp() throws IOException {
        Path pack = root.resolve("packages/rpg_gates");
        Files.createDirectories(pack);
        Files.writeString(pack.resolve("package.yml"), "enabled: true\nversion: 1.0.0\n");
        Files.writeString(pack.resolve("quests.yml"), String.join("\n",
                "quests:",
                "  veteran:",
                "    displayName: \"Veteran's Errand\"",
                "    startConditions:",
                "      - { type: requirement, provider: mysticrpg, check: level, min: 10 }",
                "    objectives:",
                "      - { id: wolves, displayName: \"Defeat wolves\", type: kill, target: Wolf_Black, amount: 2 }",
                "  shorthand:",
                "    displayName: \"Shorthand Errand\"",
                "    startConditions:",
                "      - { type: \"mysticrpg:level\", min: 10 }",
                "    objectives:",
                "      - { id: wolves, displayName: \"Defeat wolves\", type: kill, target: Wolf_Black, amount: 2 }",
                "  unknown_check:",
                "    displayName: \"Broken Errand\"",
                "    startConditions:",
                "      - { type: requirement, provider: mysticrpg, check: paragon_era, min: 1 }",
                "    objectives:",
                "      - { id: wolves, displayName: \"Defeat wolves\", type: kill, target: Wolf_Black, amount: 2 }",
                ""));
        LoadedContent content = new QuestContentLoader(MAPPER).load(root.resolve("packages"));
        MysticQuestsEventBus bus = new MysticQuestsEventBus(null);
        PlayerSessionService sessions = new PlayerSessionService(null);
        QuestNotificationService notifications = new QuestNotificationService(sessions, null);
        quests = new PlayerQuestService(() -> content, new JsonQuestStorage(root.resolve("data/players"), MAPPER),
                new ScopedStateService(new MysticStateStore(bus), null, false, false), null, null, null,
                notifications, sessions, null, null);
        quests.bindRequirements(registry.requirements());
        rpg.levels.put(NOVICE, 7);
        rpg.levels.put(VETERAN, 12);
    }

    @Test
    @DisplayName("acceptance: a quest gated on RPG level 10 starts for level 12 and not for level 7")
    void levelGate() throws Exception {
        try (AutoCloseable ignored = registry.requirements().register(rpg)) {
            assertFalse(quests.startQuest(NOVICE, "rpg_gates:veteran").success(), "level 7 < 10");
            assertTrue(quests.startQuest(VETERAN, "rpg_gates:veteran").success());
            assertFalse(quests.startQuest(NOVICE, "rpg_gates:shorthand").success());
            assertTrue(quests.startQuest(VETERAN, "rpg_gates:shorthand").success(), "namespace:check shorthand");
        }
    }

    @Test
    @DisplayName("fail closed: no provider, an unavailable one, an unknown check or an unverifiable answer deny")
    void failsClosed() throws Exception {
        assertFalse(quests.startQuest(VETERAN, "rpg_gates:veteran").success(), "no provider registered");
        try (AutoCloseable ignored = registry.requirements().register(rpg)) {
            assertFalse(quests.startQuest(VETERAN, "rpg_gates:unknown_check").success(), "unknown check");
            rpg.available = false;
            assertFalse(quests.startQuest(VETERAN, "rpg_gates:veteran").success(), "provider unavailable");
            rpg.available = true;
            UUID stranger = UUID.randomUUID();
            assertFalse(quests.startQuest(stranger, "rpg_gates:veteran").success(), "unverifiable denies");
        }
        assertEquals(QuestRequirementResult.Status.UNVERIFIABLE, registry.requirements()
                .evaluate(VETERAN, "mysticrpg", "level", Map.of("min", "1"), "").status(), "unregistered on close");
    }

    @Test
    @DisplayName("a namespace has one provider; requirement is a reserved condition type")
    void oneProviderPerNamespace() throws Exception {
        try (AutoCloseable ignored = registry.requirements().register(rpg)) {
            assertThrows(IllegalStateException.class, () -> registry.requirements().register(new FakeRpg()));
            assertTrue(registry.isRequirementShorthand("mysticrpg:level"));
            assertFalse(registry.isRequirementShorthand("other:level"));
        }
        assertFalse(registry.registerCondition("requirement", context -> true));
    }
}
