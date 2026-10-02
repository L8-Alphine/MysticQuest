package org.hyzionstudios.mysticquests.content;

import org.hyzionstudios.mysticquests.content.MigrationReport.Item;
import org.hyzionstudios.mysticquests.content.MigrationReport.Kind;
import org.hyzionstudios.mysticquests.util.Json;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §25 and the §33 checklist: existing quests migrate or produce an explicit, actionable report. */
final class MigrationReportTest {
    @TempDir
    Path packages;

    @Test
    void v1ContentIsSortedIntoUnchangedUpgradeAndManual() throws IOException {
        Path temple = Files.createDirectories(packages.resolve("temple"));
        Files.writeString(temple.resolve("package.yml"), "version: 1.0.0\n");
        Files.writeString(temple.resolve("quests.json"), """
                { "quests": [ { "id": "guardian", "objectives": [ { "id": "talk", "type": "interactNpc" } ],
                  "events": [ { "type": "spawnNpc", "npc": "guardian" }, { "type": "giveItem", "item": "gold" },
                              { "type": "setCamera", "mode": "third" }, { "type": "mysticquests:signal", "signal": "x:y" } ] } ] }
                """);
        Files.writeString(temple.resolve("narrative.yml"), """
                cutscenes:
                  - id: temple:scene
                    steps: [ { type: setCamera, mode: third } ]
                    onEnd: [ { type: setCamera, mode: first } ]
                """);
        Files.writeString(temple.resolve("broken.json"), "{ \"quests\": [ ");

        MigrationReport report = MigrationReport.scan(packages, Json.createMapper(), Json.createYamlMapper());

        List<Item> upgrades = report.items(Kind.UPGRADE);
        assertEquals(List.of("spawnNpc", "setCamera"), upgrades.stream().map(Item::type).toList(),
                "v1 patterns with a 2.0 replacement, but not the setCamera already inside a cutscene");
        assertTrue(upgrades.getFirst().advice().contains("mysticquests:entity.spawn"));
        assertEquals("quests.json", upgrades.getFirst().file().substring("temple/".length()));
        assertEquals(2, report.unchanged().get("temple"), "interactNpc and giveItem run unchanged; namespaced types are already 2.0");
        assertEquals(1, report.items(Kind.MANUAL).size());
        assertTrue(report.items(Kind.MANUAL).getFirst().file().endsWith("broken.json"));
    }

    @Test
    void theShippedExampleNeedsNoManualAction() throws IOException {
        MigrationReport report = MigrationReport.scan(Path.of("examples/packages"), Json.createMapper(), Json.createYamlMapper());
        assertTrue(report.items(Kind.MANUAL).isEmpty(), report.items().toString());
        assertTrue(report.unchanged().values().stream().mapToInt(Integer::intValue).sum() > 0);
    }
}
