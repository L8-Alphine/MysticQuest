package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleDefinition;
import org.hyzionstudios.mysticquests.narrative.puzzle.PuzzleRuleType;
import org.hyzionstudios.mysticquests.narrative.puzzle.QuestPuzzleService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.junit.jupiter.api.Test;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;

final class QuestPuzzleHudStateTest {
    @Test
    void keepsOnlyTypeAgnosticRevealedInformation() {
        QuestPuzzleHudState state = new QuestPuzzleHudState(
                "Hidden Keys",
                "The seal responds to four keys.",
                "2 / 4 progress",
                "Selection is unique to your StorySession",
                QuestPuzzleHudState.Phase.ACCEPTED,
                "The seal answers.");

        assertEquals("The seal responds to four keys.", state.hintText());
        assertEquals("2 / 4 progress", state.statusText());
        assertEquals("INPUT ACCEPTED", state.phase().label());
    }

    @Test
    void everyPuzzleRuleProjectsToHintAndStatusWithoutInputIdentities() {
        for (PuzzleRuleType type : PuzzleRuleType.values()) {
            NamespacedId id = NamespacedId.of("test", "hidden_mechanism");
            PuzzleDefinition.StateMachine machine = type == PuzzleRuleType.STATE_MACHINE
                    ? new PuzzleDefinition.StateMachine("start", Map.of(
                            "start", new PuzzleDefinition.MachineState(false, Map.of(), List.of())))
                    : null;
            PuzzleDefinition definition = new PuzzleDefinition(
                    id,
                    "test",
                    "test:story",
                    PuzzleDefinition.AudienceMode.PLAYER,
                    List.of(new PuzzleDefinition.Input("secret_input", 1, "group", null, "ENTER", false)),
                    null,
                    new PuzzleDefinition.Rule(type, 1, List.of("secret_input"), Duration.ofSeconds(5),
                            1, 1, false, machine),
                    null,
                    false,
                    List.of(), List.of(), List.of(), List.of());
            QuestPuzzleService.PuzzleView view = new QuestPuzzleService.PuzzleView(
                    id,
                    "session",
                    SessionOwner.player(UUID.randomUUID()),
                    0,
                    42L,
                    List.of("secret_input"),
                    Map.of(),
                    List.of(),
                    type == PuzzleRuleType.STATE_MACHINE ? "start" : null,
                    false,
                    false);

            QuestPuzzleHudState state = QuestPuzzleHudState.from(
                    definition, view, QuestPuzzleService.Outcome.ACCEPTED);

            assertFalse(state.hintText().isBlank(), type + " needs a hint");
            assertFalse(state.statusText().isBlank(), type + " needs a status");
            assertFalse(state.hintText().contains("secret_input"), type + " leaked an input id");
            assertFalse(state.statusText().contains("secret_input"), type + " leaked an input id");
        }
    }

    @Test
    void thePlayerIsToldWhoseProgressItIs() {
        NamespacedId id = NamespacedId.of("test", "shared_seal");
        PuzzleDefinition definition = new PuzzleDefinition(
                id, "test", "test:story", PuzzleDefinition.AudienceMode.PARTY,
                List.of(new PuzzleDefinition.Input("a", 1, null, null, "ENTER", false)),
                null,
                new PuzzleDefinition.Rule(PuzzleRuleType.ALL, 1, List.of(), Duration.ofSeconds(5),
                        1, 1, false, null),
                null, false, List.of(), List.of(), List.of(), List.of());

        QuestPuzzleHudState party = QuestPuzzleHudState.from(definition,
                view(id, SessionOwner.party("party-1")), QuestPuzzleService.Outcome.ACCEPTED);
        QuestPuzzleHudState personal = QuestPuzzleHudState.from(definition,
                view(id, SessionOwner.player(UUID.randomUUID())), QuestPuzzleService.Outcome.ACCEPTED);

        assertEquals("Shared with your party", party.sessionLabel());
        assertEquals("Personal progress, saved", personal.sessionLabel());
        assertFalse(personal.sessionLabel().contains("StorySession"),
                "Runtime vocabulary is not player vocabulary");
    }

    private static QuestPuzzleService.PuzzleView view(NamespacedId id, SessionOwner owner) {
        return new QuestPuzzleService.PuzzleView(
                id, "session", owner, 0, 42L, List.of("a"), Map.of(), List.of(), null, false, false);
    }

    @Test
    void partyMembersSeeSharedProgressWithoutBeingToldTheyActed() {
        QuestPuzzleHudState actor = new QuestPuzzleHudState(
                "Hidden Keys", "The seal responds to four keys.", "2 / 4 progress", "Shared with your party",
                QuestPuzzleHudState.Phase.ACCEPTED, "The mechanism responds.");

        QuestPuzzleHudState teammate = actor.forPartyMember();

        assertEquals("2 / 4 progress", teammate.statusText());
        assertEquals(QuestPuzzleHudState.Phase.ACCEPTED, teammate.phase());
        assertEquals("A party member moved the mechanism.", teammate.feedback());
    }
}
