package org.hyzionstudios.mysticquests.integration.mysticidentity;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime.Milestone;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.PlayerQuestService.CompletedQuest;
import org.hyzionstudios.mysticquests.service.PlayerQuestService.PlayerJournal;

import org.hyzionstudios.mysticidentity.api.portal.PortalBlock;
import org.hyzionstudios.mysticidentity.api.portal.PortalHealth;
import org.hyzionstudios.mysticidentity.api.portal.PortalManifest;
import org.hyzionstudios.mysticidentity.api.portal.PortalRequest;
import org.hyzionstudios.mysticidentity.api.portal.PortalView;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CompletionException;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §19.1: the player portal shows active quests, history and milestones, read-only and never empty on failure. */
final class QuestsPortalProviderTest {
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-00000000000a");

    private static final JournalEntry WOLVES = JournalEntry.ungrouped("greenvale:wolves", "Wolf Trouble",
            "Thin out the wolves.", List.of(ObjectiveView.of("hunt", "Defeat wolves", 3, 5)), false);
    private static final JournalEntry HERBS = JournalEntry.ungrouped("greenvale:herbs", "Herb Run",
            "", List.of(ObjectiveView.of("wheat", "Bring wheat", 10, 10)), false);

    private static final PlayerJournal JOURNAL = new PlayerJournal(List.of(HERBS, WOLVES), "greenvale:wolves",
            List.of(new CompletedQuest("greenvale:intro", "Welcome", Instant.parse("2026-10-01T12:00:00Z"))));

    private static QuestsPortalProvider provider(boolean available, boolean failing) {
        return new QuestsPortalProvider(new QuestPortalSource() {
            @Override
            public boolean available() {
                return available;
            }

            @Override
            public PlayerJournal journal(UUID player) throws IOException {
                if (failing) {
                    throw new IOException("storage offline");
                }
                return JOURNAL;
            }

            @Override
            public List<Milestone> milestones(UUID player) {
                return List.of(new Milestone(NamespacedId.of("grove", "seal_broken"), "Broke the grove seal",
                        Instant.parse("2026-10-02T09:00:00Z")));
            }
        });
    }

    private static PortalRequest request(PortalRequest.Kind kind, String target) {
        return new PortalRequest(UUID.randomUUID(), PLAYER, PortalRequest.Relation.SELF, kind, target,
                Set.of(QuestsPortalProvider.VIEW), Locale.UK, Map.of(), "test");
    }

    private static List<PortalBlock> render(QuestsPortalProvider provider, PortalRequest.Kind kind, String target) {
        PortalView view = provider.render(request(kind, target)).toCompletableFuture().join();
        return view.blocks();
    }

    @Test
    void theManifestFollowsThePortalRules() {
        PortalManifest manifest = provider(true, false).manifest();
        assertTrue(manifest.characterScoped(), "quest data belongs to the player's character");
        assertTrue(manifest.capabilityNames().stream().allMatch(name -> name.startsWith("quests.")), "capabilities carry the module id");
        assertEquals(List.of("active", "history", "story"),
                manifest.areas().getFirst().pages().stream().map(PortalManifest.Page::id).toList());
    }

    @Test
    void theWidgetLeadsWithTheTrackedQuestAndItsNextObjective() {
        List<PortalBlock> blocks = render(provider(true, false), PortalRequest.Kind.WIDGET, "tracked");
        PortalBlock.Stats quest = assertInstanceOf(PortalBlock.Stats.class, blocks.getFirst());
        assertEquals("Wolf Trouble", quest.stats().getFirst().value(), "the tracked quest, not the first in the list");
        PortalBlock.Progress next = assertInstanceOf(PortalBlock.Progress.class, blocks.get(1));
        assertEquals("Defeat wolves", next.label());
        assertEquals(3, next.current());
    }

    @Test
    void pagesShowActiveQuestsHistoryAndMilestones() {
        QuestsPortalProvider provider = provider(true, false);
        List<PortalBlock> active = render(provider, PortalRequest.Kind.PAGE, "active");
        assertEquals(2, active.size(), "one section per active quest");
        assertTrue(((PortalBlock.Section) active.get(1)).title().endsWith("(tracked)"));

        PortalBlock.Table history = assertInstanceOf(PortalBlock.Table.class,
                render(provider, PortalRequest.Kind.PAGE, "history").getFirst());
        assertEquals("Welcome", history.rows().getFirst().getFirst());

        PortalBlock.Items story = assertInstanceOf(PortalBlock.Items.class,
                render(provider, PortalRequest.Kind.PAGE, "story").getFirst());
        assertEquals("Broke the grove seal", story.items().getFirst().title());

        PortalBlock.Stats card = assertInstanceOf(PortalBlock.Stats.class,
                render(provider, PortalRequest.Kind.CARD, QuestsPortalProvider.AREA).getFirst());
        assertEquals("2", card.stats().getFirst().value());
    }

    @Test
    void aFailedReadIsUnavailableNeverAnEmptyQuestLog() {
        QuestsPortalProvider provider = provider(true, true);
        assertThrows(CompletionException.class,
                () -> provider.render(request(PortalRequest.Kind.PAGE, "active")).toCompletableFuture().join());
        assertEquals(PortalHealth.State.OFFLINE, provider(false, false).health().state());
    }
}
