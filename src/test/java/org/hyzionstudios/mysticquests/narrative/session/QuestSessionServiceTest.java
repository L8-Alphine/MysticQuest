package org.hyzionstudios.mysticquests.narrative.session;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.MutableClock;
import org.hyzionstudios.mysticquests.narrative.persistence.InMemoryDocumentStore;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import com.fasterxml.jackson.databind.node.JsonNodeFactory;
import com.fasterxml.jackson.databind.node.ObjectNode;
import org.junit.jupiter.api.Test;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §3.1, §8.1 and §22: session persistence, recovery, checkpoints and party semantics. */
final class QuestSessionServiceTest {
    private static final UUID ALICE = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID BOB = UUID.fromString("00000000-0000-4000-8000-00000000000b");

    private final InMemoryDocumentStore store = new InMemoryDocumentStore();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-02T12:00:00Z"));
    private final List<String> problems = new ArrayList<>();
    private final AtomicInteger ids = new AtomicInteger();

    private QuestSessionService service() {
        return new QuestSessionService(store, clock, "server-a", problems::add, () -> "qs-" + ids.incrementAndGet());
    }

    @Test
    void oneActiveSessionPerOwnerAndStory() {
        QuestSessionService sessions = service();
        QuestSession first = sessions.open(SessionOwner.player(ALICE), "druid_temple", "1.0");
        assertEquals(first, sessions.open(SessionOwner.player(ALICE), "druid_temple", "1.0"));
        assertNotEquals(first, sessions.open(SessionOwner.player(ALICE), "other_story", "1.0"));
        assertNotEquals(first, sessions.open(SessionOwner.player(BOB), "druid_temple", "1.0"));

        sessions.complete(first);
        QuestSession replay = sessions.open(SessionOwner.player(ALICE), "druid_temple", "1.0");
        assertNotEquals(first.id(), replay.id(), "a completed story starts a fresh session");
    }

    @Test
    void sessionsSurviveARestartWithStateComponentsAndLedger() {
        QuestSessionService before = service();
        QuestSession session = before.open(SessionOwner.player(ALICE), "druid_temple", "1.0");
        session.state().putVariable(id("hyzion:x"), new IntValue(3));
        session.state().setTriggerOverride("avalon:gate", false);
        session.record("puzzle:a:g0:complete", false);
        session.record("puzzle:a:g0:complete#reward", true);
        session.putComponent("future/subsystem", () -> JsonNodeFactory.instance.objectNode().put("kept", true));
        session.setCurrentNode("TemplePuzzle");
        before.flush();

        QuestSessionService after = service();
        QuestSession restored = after.active(SessionOwner.player(ALICE), "druid_temple").orElseThrow();
        assertEquals(session.id(), restored.id());
        assertEquals(new IntValue(3), restored.state().variable(id("hyzion:x")));
        assertEquals(Boolean.FALSE, restored.state().triggerOverride("avalon:gate"));
        assertTrue(restored.applied("puzzle:a:g0:complete"));
        assertTrue(restored.applied("puzzle:a:g0:complete#reward"));
        assertEquals("TemplePuzzle", restored.currentNode());
        assertEquals("server-a", restored.lastServer());
        assertTrue(restored.componentKeys().contains("future/subsystem"),
                "components owned by subsystems this build does not know are kept verbatim");
    }

    @Test
    void releaseWritesAndUnloadsAndLoadRestores() {
        QuestSessionService sessions = service();
        QuestSession session = sessions.open(SessionOwner.player(ALICE), "story", "1");
        session.state().putVariable(id("hyzion:x"), new IntValue(1));
        sessions.release(SessionOwner.player(ALICE));
        assertTrue(sessions.get(session.id()).isEmpty(), "unloaded");
        assertFalse(sessions.hasPendingChanges());
        assertEquals(new IntValue(1), sessions.load(SessionOwner.player(ALICE)).getFirst().state().variable(id("hyzion:x")));
    }

    @Test
    void checkpointsRestoreStateAndKeepPermanentKeys() {
        QuestSession session = service().open(SessionOwner.player(ALICE), "story", "1");
        session.state().putVariable(id("hyzion:x"), new IntValue(1));
        session.checkpoint("act2", clock.instant());
        session.state().putVariable(id("hyzion:x"), new IntValue(9));
        session.record("ordinary", false);
        session.record("reward", true);
        session.setCurrentNode("Later");

        assertTrue(session.rollback("act2", problems));
        assertEquals(new IntValue(1), session.state().variable(id("hyzion:x")));
        assertFalse(session.applied("ordinary"));
        assertTrue(session.applied("reward"));
        assertNull(session.currentNode());
        assertFalse(session.rollback("missing", problems));
    }

    @Test
    void checkpointsAreBounded() {
        QuestSession session = service().open(SessionOwner.player(ALICE), "story", "1");
        for (int index = 0; index < QuestSession.MAX_CHECKPOINTS + 5; index++) {
            session.checkpoint("c" + index, clock.instant());
        }
        assertEquals(QuestSession.MAX_CHECKPOINTS, session.checkpoints().size());
        assertEquals("c5", session.checkpoints().getFirst().label(), "the oldest are dropped first");
    }

    @Test
    void leavingAPartyForksTheStoryUnderTheForkPolicy() {
        QuestSessionService sessions = service();
        QuestSession party = sessions.open(SessionOwner.party("p1"), "druid_temple", "1");
        party.state().putVariable(id("hyzion:keys"), new IntValue(3));
        party.record("reward-step", true);

        List<QuestSession> forks = sessions.onMemberLeft("p1", ALICE, PartyExitPolicy.FORK);
        assertEquals(1, forks.size());
        QuestSession solo = sessions.active(SessionOwner.player(ALICE), "druid_temple").orElseThrow();
        assertEquals(new IntValue(3), solo.state().variable(id("hyzion:keys")), "progress so far comes along");
        assertTrue(solo.applied("reward-step"), "and rewards already earned are not paid again");
        assertTrue(party.active(), "the party keeps its own session");

        solo.state().putVariable(id("hyzion:keys"), new IntValue(4));
        assertEquals(new IntValue(3), party.state().variable(id("hyzion:keys")), "the copy is independent");

        assertTrue(sessions.onMemberLeft("p1", ALICE, PartyExitPolicy.FORK).isEmpty(),
                "no second copy when they already have their own session");
        assertTrue(sessions.onMemberLeft("p1", BOB, PartyExitPolicy.DETACH).isEmpty());
        assertTrue(sessions.active(SessionOwner.player(BOB), "druid_temple").isEmpty());
    }

    @Test
    void disbandingForksEveryMemberAndArchivesThePartySession() {
        QuestSessionService sessions = service();
        QuestSession party = sessions.open(SessionOwner.party("p1"), "story", "1");
        sessions.onPartyDisbanded("p1", List.of(ALICE, BOB), PartyExitPolicy.FORK);
        assertEquals(SessionStatus.ARCHIVED, party.status());
        assertTrue(sessions.active(SessionOwner.player(ALICE), "story").isPresent());
        assertTrue(sessions.active(SessionOwner.player(BOB), "story").isPresent());
        assertTrue(sessions.active(SessionOwner.party("p1"), "story").isEmpty(), "a reused party id starts fresh");
    }

    /** A document from a newer release is never loaded, and never overwritten. */
    @Test
    void documentsFromANewerReleaseAreQuarantined() throws Exception {
        QuestSessionService writer = service();
        QuestSession session = writer.open(SessionOwner.player(ALICE), "story", "1");
        writer.flush();
        ObjectNode future = store.read(QuestSessionService.SESSIONS, session.id()).orElseThrow();
        future.put("schemaVersion", 99);
        store.write(QuestSessionService.SESSIONS, session.id(), future);

        QuestSessionService reader = service();
        assertTrue(reader.active(SessionOwner.player(ALICE), "story").isEmpty());
        assertTrue(problems.stream().anyMatch(problem -> problem.contains("quarantined")), problems.toString());
        reader.flush();
        assertEquals(99, store.read(QuestSessionService.SESSIONS, session.id()).orElseThrow().get("schemaVersion").intValue(),
                "the newer document is left untouched");
    }

    /**
     * A puzzle output runs under its session's monitor and may open another session; a party change
     * on another world thread forks that same session. Under the old lock order (service lock, then
     * session) these two deadlocked.
     */
    @Test
    void openingFromInsideASessionCannotDeadlockAgainstAPartyChange() throws Exception {
        QuestSessionService sessions = service();
        QuestSession party = sessions.open(SessionOwner.party("p1"), "story", "1");
        CountDownLatch holding = new CountDownLatch(1);
        CountDownLatch proceed = new CountDownLatch(1);
        ExecutorService threads = Executors.newFixedThreadPool(2);
        try {
            Future<?> output = threads.submit(() -> {
                synchronized (party) {
                    holding.countDown();
                    proceed.await();
                    sessions.open(SessionOwner.player(BOB), "other_story", "1");
                }
                return null;
            });
            holding.await();
            Future<?> leave = threads.submit(() -> sessions.onMemberLeft("p1", ALICE, PartyExitPolicy.FORK));
            Thread.sleep(100);
            proceed.countDown();
            output.get(5, TimeUnit.SECONDS);
            leave.get(5, TimeUnit.SECONDS);
        } finally {
            threads.shutdownNow();
        }
        assertTrue(sessions.active(SessionOwner.player(ALICE), "story").isPresent());
    }

    @Test
    void sessionScopeStateIsReachableThroughTheService() {
        QuestSessionService sessions = service();
        QuestSession session = sessions.open(SessionOwner.player(ALICE), "story", "1");
        assertSame(session.state(), sessions.sessionState(session.id()));
        assertNull(sessions.sessionState("qs-unknown"));
    }
}
