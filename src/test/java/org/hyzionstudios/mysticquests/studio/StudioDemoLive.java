package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.TagRecord;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.ObjectiveLine;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.QuestLine;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin.QuestStatus;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.function.Function;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;

/**
 * Sample live state for the Studio dev launcher, so the Players page can be worked on without a
 * game server. Story state is real — an in-memory narrative runtime behind a real
 * {@link PlayerStateAdmin} — and v1 quests are sample data; v1 changes answer that they need a
 * game server.
 */
final class StudioDemoLive implements StudioLive {
    static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000a11c");

    private final NarrativeTestKit kit = new NarrativeTestKit();
    private final PlayerStateAdmin admin;

    StudioDemoLive() {
        kit.load("""
                {
                  "tagSchemas": [ { "id": "avalon:temple.entered", "scope": "player" } ],
                  "variableSchemas": [ { "id": "avalon:keys_found", "type": "integer", "scope": "player", "default": 0 } ]
                }
                """);
        var player = kit.runtime().store().state(ScopeOwner.player(ALICE));
        player.putVariable(id("avalon:keys_found"), new QuestValue.IntValue(2));
        player.putVariable(id("mysticquests:ui.tracker"), new QuestValue.StringValue("expanded"));
        player.putTag(new TagRecord(id("avalon:temple.entered"), kit.clock.instant(), null, "demo"), kit.clock.instant());
        kit.runtime().sessions().open(SessionOwner.player(ALICE), "avalon:first_breach", "v1");
        admin = new PlayerStateAdmin(null, null, kit::runtime,
                (actor, action, target, session, before, after, reason) ->
                        System.out.println("audit " + action + " " + before + " -> " + after + " (" + reason + ")"),
                uuid -> uuid.equals(ALICE) ? Optional.of("Alice") : Optional.empty(), ALICE::equals);
    }

    @Override
    public Overview overview() {
        return new Overview(List.of(new OnlinePlayer(ALICE, "Alice", 1)), kit.runtime().metricsSnapshot(), List.of());
    }

    @Override
    public Map<String, List<String>> player(UUID player) {
        return kit.runtime().debug().snapshot(player);
    }

    @Override
    public Optional<PlayerStateAdmin.Snapshot> playerState(UUID player) {
        PlayerStateAdmin.StoryState story = admin.story(player);
        List<QuestLine> quests = new ArrayList<>();
        if (player.equals(ALICE)) {
            quests.add(new QuestLine("avalon:first_breach", "The First Breach", QuestStatus.TRACKED, "2 / 4 objectives",
                    Instant.parse("2026-10-05T09:12:00Z"), List.of(
                            new ObjectiveLine("keys", "Recover the four hidden Vaelith keys", 2, 4),
                            new ObjectiveLine("inscription", "Decode the surface inscription", 0, 1))));
            quests.add(new QuestLine("avalon:whispers", "Whispers Beneath Avalon", QuestStatus.ACTIVE, "0 / 3 objectives",
                    Instant.parse("2026-10-05T10:40:00Z"), List.of()));
            quests.add(new QuestLine("starter:welcome", "Welcome to Hyzion", QuestStatus.COMPLETED, "Completed",
                    Instant.parse("2026-10-01T18:02:00Z"), List.of()));
            quests.add(new QuestLine("starter:fishing", "A Quiet Line", QuestStatus.ABANDONED, "Abandoned",
                    Instant.parse("2026-10-03T12:30:00Z"), List.of()));
        }
        return Optional.of(new PlayerStateAdmin.Snapshot(player, player.equals(ALICE) ? "Alice" : player.toString(),
                player.equals(ALICE), quests, new TreeSet<>(List.of("starter.welcomed", "avalon.visited")),
                new TreeMap<>(Map.of("starter.boat", "docked")), story.narrative(), story.variables(), story.tags(),
                story.sessions(), story.problems()));
    }

    @Override
    public Optional<UUID> findPlayer(String nameOrUuid) {
        if (nameOrUuid.equalsIgnoreCase("alice")) {
            return Optional.of(ALICE);
        }
        try {
            return Optional.of(UUID.fromString(nameOrUuid.trim()));
        } catch (IllegalArgumentException notUuid) {
            return Optional.empty();
        }
    }

    @Override
    public PlayerStateAdmin.Outcome changePlayer(UUID player, Function<PlayerStateAdmin, PlayerStateAdmin.Outcome> change) {
        try {
            return change.apply(admin);
        } catch (NullPointerException noGameServer) {
            return new PlayerStateAdmin.Outcome(false, "v1 quests, tags and variables need a game server; the dev launcher only has story state.");
        }
    }
}
