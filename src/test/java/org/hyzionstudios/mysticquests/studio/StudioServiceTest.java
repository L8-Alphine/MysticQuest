package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.MutableClock;
import org.hyzionstudios.mysticquests.studio.StudioAuth.Session;
import org.hyzionstudios.mysticquests.studio.StudioReleases.Release;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace.Change;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §19.2 and §23: sign-in, separable permissions enforced on the server, drafts, validated publish and rollback. */
final class StudioServiceTest {
    private static final UUID WRITER = UUID.fromString("00000000-0000-4000-8000-0000000000a1");
    private static final UUID LEAD = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final String QUESTS = "packages/greenvale/quests.yml";
    private static final String ORIGINAL = """
            quests:
              - id: wolves
                displayName: Wolf Trouble
            conversations:
              - id: elder
                text: Hello
            """;

    @TempDir
    Path data;

    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-04T12:00:00Z"));
    private final Map<UUID, Set<String>> granted = new HashMap<>();
    private final List<String> log = new ArrayList<>();
    private final AtomicInteger reloads = new AtomicInteger();
    private boolean reloadFails;
    private boolean draftValid = true;
    private Path live;
    private StudioService studio;
    /** Changes the Players page asked the server to make, by player. */
    private final List<UUID> changed = new ArrayList<>();
    private final StudioLive server = new StudioLive() {
        @Override
        public Overview overview() {
            return new Overview(List.of(), null, List.of());
        }

        @Override
        public Map<String, List<String>> player(UUID player) {
            return Map.of();
        }

        @Override
        public org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome changePlayer(UUID player,
                java.util.function.Function<org.hyzionstudios.mysticquests.service.PlayerStateAdmin,
                        org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome> change) {
            changed.add(player);
            return new org.hyzionstudios.mysticquests.service.PlayerStateAdmin.Outcome(true, "done");
        }
    };

    @BeforeEach
    void setUp() throws IOException {
        live = data.resolve("packages");
        Files.createDirectories(live.resolve("greenvale"));
        Files.writeString(live.resolve("greenvale/quests.yml"), ORIGINAL);
        ObjectMapper json = new ObjectMapper();
        Path studioRoot = data.resolve("studio");
        StudioWorkspace workspace = new StudioWorkspace(ContentRoot.live(live), studioRoot, json, Json.createYamlMapper());
        StudioValidation.Validator validator = draft -> draftValid
                ? new StudioValidation(true, 1, 1, List.of())
                : new StudioValidation(false, 1, 1, List.of(new StudioValidation.Problem("error", "MISSING_REFERENCE", "greenvale", "broken")));
        StudioReleases releases = new StudioReleases(studioRoot, workspace, validator, () -> {
            reloads.incrementAndGet();
            if (reloadFails) {
                throw new IOException("quest wolves has no objectives");
            }
        }, clock, json);
        StudioAudit audit = new StudioAudit(studioRoot.resolve("audit.jsonl"), clock, json, log::add);
        studio = new StudioService(new StudioAuth(clock), workspace, releases, audit,
                (player, permission) -> granted.getOrDefault(player, Set.of()).contains(permission), validator, "development",
                server, clock);
        grant(WRITER, StudioCapability.LOGIN, StudioCapability.VIEW, StudioCapability.DIALOGUE);
        grant(LEAD, StudioCapability.LOGIN, StudioCapability.EDIT, StudioCapability.PUBLISH);
    }

    private void grant(UUID player, StudioCapability... capabilities) {
        Set<String> permissions = granted.computeIfAbsent(player, ignored -> new HashSet<>());
        for (StudioCapability capability : capabilities) {
            permissions.add(capability.permission());
        }
    }

    private Session signIn(UUID player) throws StudioException {
        return studio.signIn(studio.auth().issueCode(player, "player-" + player.toString().substring(34)), "127.0.0.1");
    }

    @Test
    void codesAreSingleUseShortLivedAndGuessingIsLimited() throws StudioException {
        String code = studio.auth().issueCode(WRITER, "writer");
        studio.signIn(code, "10.0.0.1");
        assertThrows(StudioException.class, () -> studio.signIn(code, "10.0.0.1"), "a code works once");

        String late = studio.auth().issueCode(WRITER, "writer");
        clock.advance(StudioAuth.CODE_TTL.plusSeconds(1));
        assertThrows(StudioException.class, () -> studio.signIn(late, "10.0.0.1"), "and only for a few minutes");

        for (int attempt = 0; attempt < StudioAuth.MAX_FAILURES; attempt++) {
            assertThrows(StudioException.class, () -> studio.signIn("WRONGCODE1", "10.0.0.9"));
        }
        String fresh = studio.auth().issueCode(WRITER, "writer");
        StudioException limited = assertThrows(StudioException.class, () -> studio.signIn(fresh, "10.0.0.9"));
        assertEquals(StudioException.Status.TOO_MANY_REQUESTS, limited.status(), "an address that keeps guessing is stopped");
    }

    @Test
    void sessionsExpireAndLosingTheLoginPermissionEndsAccessAtOnce() throws Exception {
        Session session = signIn(WRITER);
        assertTrue(studio.auth().session(session.token()).isPresent());
        clock.advance(StudioAuth.IDLE_TIMEOUT.plus(Duration.ofMinutes(1)));
        assertTrue(studio.auth().session(session.token()).isEmpty(), "an idle session expires");

        Session again = signIn(WRITER);
        granted.get(WRITER).remove(StudioCapability.LOGIN.permission());
        StudioException removed = assertThrows(StudioException.class, () -> studio.files(again));
        assertEquals(StudioException.Status.UNAUTHORIZED, removed.status());
        assertTrue(studio.auth().session(again.token()).isEmpty());
    }

    @Test
    void pathsCannotLeaveTheContentFolders() {
        for (String path : List.of("packages/../config.json", "packages/greenvale/../../config.yml", "/etc/passwd.yml",
                "packages\\greenvale\\quests.yml", "packages/.hidden/quests.yml", "data/quests.yml", "packages/greenvale/run.sh",
                "packages/greenvale/..yml")) {
            StudioException refused = assertThrows(StudioException.class, () -> ContentRoot.validate(path), path);
            assertEquals(StudioException.Status.BAD_REQUEST, refused.status());
        }
    }

    @Test
    void editsStayInTheDraftAndConcurrentEditsConflict() throws Exception {
        Session lead = signIn(LEAD);
        grant(LEAD, StudioCapability.VIEW);
        StudioWorkspace.FileContent opened = studio.read(lead, QUESTS);
        String edited = ORIGINAL.replace("Wolf Trouble", "Wolf Hunt");
        String version = studio.write(lead, QUESTS, edited, opened.version());
        assertEquals(ORIGINAL, Files.readString(live.resolve("greenvale/quests.yml")), "players see nothing until a publish");
        assertEquals(List.of(new Change(QUESTS, Change.Kind.CHANGED)), studio.status(lead).changes());

        StudioException stale = assertThrows(StudioException.class,
                () -> studio.write(lead, QUESTS, ORIGINAL.replace("Wolf Trouble", "Wolves!"), opened.version()));
        assertEquals(StudioException.Status.CONFLICT, stale.status(), "an edit based on an old version is refused");
        assertNotEquals(opened.version(), version);
        assertThrows(StudioException.class, () -> studio.write(lead, QUESTS, ORIGINAL, null), "creating over an existing file is refused");
        assertThrows(StudioException.class, () -> studio.write(lead, "packages/greenvale/broken.yml", "quests: [", null),
                "unparseable content never reaches the draft");
    }

    @Test
    void aWriterCanChangeDialogueButNotTheQuestsBesideIt() throws Exception {
        Session writer = signIn(WRITER);
        StudioWorkspace.FileContent opened = studio.read(writer, QUESTS);
        String version = studio.write(writer, QUESTS, ORIGINAL.replace("text: Hello", "text: Well met"), opened.version());

        StudioException refused = assertThrows(StudioException.class,
                () -> studio.write(writer, QUESTS, ORIGINAL.replace("text: Hello", "text: Well met").replace("Wolf Trouble", "Free gold"), version));
        assertEquals(StudioException.Status.FORBIDDEN, refused.status());
        assertTrue(refused.getMessage().contains(StudioCapability.EDIT.permission()), refused.getMessage());
        StudioException publish = assertThrows(StudioException.class, () -> studio.publish(writer, "mine", false));
        assertEquals(StudioException.Status.FORBIDDEN, publish.status(), "writing is not publishing");
        assertTrue(log.stream().anyMatch(line -> line.contains("edit refused")), "refusals are audited");
    }

    @Test
    void publishingIsValidatedRecordedAndCanBeRolledBack() throws Exception {
        Session lead = signIn(LEAD);
        grant(LEAD, StudioCapability.VIEW);
        String opened = studio.read(lead, QUESTS).version();
        studio.write(lead, QUESTS, ORIGINAL.replace("Wolf Trouble", "Wolf Hunt"), opened);

        draftValid = false;
        StudioException invalid = assertThrows(StudioException.class, () -> studio.publish(lead, "rename", false));
        assertInstanceOf(StudioValidation.class, invalid.detail(), "the client gets the problems to show");
        assertEquals(0, reloads.get());

        draftValid = true;
        Release first = studio.publish(lead, "rename", false);
        assertEquals(1, first.number());
        assertTrue(Files.readString(live.resolve("greenvale/quests.yml")).contains("Wolf Hunt"));
        assertEquals(1, reloads.get());
        List<Release> history = studio.history(lead);
        assertTrue(history.getFirst().baseline(), "the content before the Studio is kept as release 0");
        assertEquals(List.of(new Change(QUESTS, Change.Kind.CHANGED)), studio.compare(lead, 0, 1));

        studio.restore(lead, 0);
        assertEquals(ORIGINAL, studio.read(lead, QUESTS).text(), "rolling back loads the old release into the draft");
        Release rollback = studio.publish(lead, "roll back", false);
        assertEquals(2, rollback.number(), "a rollback is a new release; history is never rewritten");
        assertEquals(ORIGINAL, Files.readString(live.resolve("greenvale/quests.yml")));
    }

    @Test
    void aRefusedReloadPutsThePreviousContentBack() throws Exception {
        Session lead = signIn(LEAD);
        grant(LEAD, StudioCapability.VIEW);
        studio.write(lead, QUESTS, ORIGINAL.replace("Wolf Trouble", "Wolf Hunt"), studio.read(lead, QUESTS).version());
        reloadFails = true;
        StudioException refused = assertThrows(StudioException.class, () -> studio.publish(lead, "bad", false));
        assertTrue(refused.getMessage().contains("has no objectives"), refused.getMessage());
        assertEquals(ORIGINAL, Files.readString(live.resolve("greenvale/quests.yml")), "the live files are restored");
        assertEquals(2, reloads.get(), "and reloaded");
        assertTrue(studio.history(lead).stream().noneMatch(release -> release.number() > 0), "a refused release is not recorded");
    }

    @Test
    void changingAPlayerNeedsThePlayersPermissionAndIsRecorded() throws Exception {
        UUID watcher = new UUID(0xCAFEL, 1);
        UUID support = new UUID(0xCAFEL, 2);
        UUID target = new UUID(0xCAFEL, 3);
        grant(watcher, StudioCapability.LOGIN, StudioCapability.LIVE);
        grant(support, StudioCapability.LOGIN, StudioCapability.PLAYERS);
        ObjectMapper json = new ObjectMapper();
        var clear = json.readTree("{ \"action\": \"clear\", \"parts\": [\"STORY_STATE\"], \"reason\": \"stuck intro\" }");

        Session watching = signIn(watcher);
        assertEquals(StudioException.Status.NOT_FOUND,
                assertThrows(StudioException.class, () -> studio.playerState(watching, target)).status(),
                "live rights read players (this server has none attached)");
        assertEquals(StudioException.Status.FORBIDDEN,
                assertThrows(StudioException.class, () -> studio.changePlayer(watching, target, clear)).status(),
                "reading a player is not changing one");
        assertEquals(StudioException.Status.FORBIDDEN,
                assertThrows(StudioException.class, () -> studio.changePlayer(signIn(WRITER), target, clear)).status());
        assertTrue(changed.isEmpty());

        Session supporting = signIn(support);
        assertTrue(StudioCapability.LIVE.grantedTo(support, (player, permission) ->
                granted.getOrDefault(player, Set.of()).contains(permission)), "changing a player includes seeing them");
        assertTrue(studio.changePlayer(supporting, target, clear).ok());
        assertEquals(List.of(target), changed);
        assertTrue(log.stream().anyMatch(line -> line.contains("player clear") && line.contains("stuck intro")),
                "the Studio's own log records who asked, and why");

        assertEquals(StudioException.Status.BAD_REQUEST, assertThrows(StudioException.class, () -> studio.changePlayer(supporting,
                target, json.readTree("{ \"action\": \"teleport\", \"reason\": \"x\" }"))).status());
        assertEquals(StudioException.Status.BAD_REQUEST, assertThrows(StudioException.class, () -> studio.changePlayer(supporting,
                target, json.readTree("{ \"action\": \"clear\", \"parts\": [\"everything\"], \"reason\": \"x\" }"))).status());
        assertEquals(1, changed.size(), "refused requests never reach the server");
    }

    @Test
    void liveStateNeedsItsOwnPermissionAndWatchersAreLimited() throws Exception {
        Session writer = signIn(WRITER);
        StudioException refused = assertThrows(StudioException.class, () -> studio.liveOverview(writer));
        assertEquals(StudioException.Status.FORBIDDEN, refused.status(), "editing rights are not live rights");

        UUID[] watchers = new UUID[StudioService.MAX_LIVE_OBSERVERS + 1];
        for (int index = 0; index < watchers.length; index++) {
            watchers[index] = new UUID(0xBEEFL, index);
            grant(watchers[index], StudioCapability.LOGIN, StudioCapability.LIVE);
        }
        for (int index = 0; index < StudioService.MAX_LIVE_OBSERVERS; index++) {
            studio.liveOverview(signIn(watchers[index]));
        }
        Session oneTooMany = signIn(watchers[StudioService.MAX_LIVE_OBSERVERS]);
        StudioException full = assertThrows(StudioException.class, () -> studio.liveOverview(oneTooMany));
        assertEquals(StudioException.Status.TOO_MANY_REQUESTS, full.status());
        clock.advance(StudioService.OBSERVER_WINDOW.plusSeconds(1));
        studio.liveOverview(oneTooMany);
        assertEquals(1, studio.liveObservers(), "watchers who stopped polling free their place");
    }

    @Test
    void liveEditsMadeOutsideTheStudioAreNotOverwrittenSilently() throws Exception {
        Session lead = signIn(LEAD);
        grant(LEAD, StudioCapability.VIEW);
        studio.write(lead, QUESTS, ORIGINAL.replace("Wolf Trouble", "Wolf Hunt"), studio.read(lead, QUESTS).version());
        Files.writeString(live.resolve("greenvale/hotfix.yml"), "quests: []\n");

        StudioException drift = assertThrows(StudioException.class, () -> studio.publish(lead, "rename", false));
        assertEquals(StudioException.Status.CONFLICT, drift.status());
        assertEquals(List.of(new Change("packages/greenvale/hotfix.yml", Change.Kind.ADDED)), studio.status(lead).liveEdits());

        studio.publish(lead, "rename, replacing the hotfix", true);
        assertFalse(Files.exists(live.resolve("greenvale/hotfix.yml")), "overwrite makes live match the draft");
        assertTrue(studio.status(lead).liveEdits().isEmpty(), "and the draft is based on the new live content");
    }
}
