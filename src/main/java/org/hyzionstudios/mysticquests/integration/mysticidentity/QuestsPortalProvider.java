package org.hyzionstudios.mysticquests.integration.mysticidentity;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime.Milestone;
import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.ObjectiveView;
import org.hyzionstudios.mysticquests.service.PlayerQuestService.PlayerJournal;
import org.hyzionstudios.mysticquests.service.StageView;

import org.hyzionstudios.mysticidentity.api.portal.PortalBlock;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock.Item;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock.Stat;
import org.hyzionstudios.mysticidentity.api.portal.PortalBlock.Tone;
import org.hyzionstudios.mysticidentity.api.portal.PortalHealth;
import org.hyzionstudios.mysticidentity.api.portal.PortalManifest;
import org.hyzionstudios.mysticidentity.api.portal.PortalProvider;
import org.hyzionstudios.mysticidentity.api.portal.PortalRequest;
import org.hyzionstudios.mysticidentity.api.portal.PortalView;

import java.io.IOException;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;

/**
 * MysticQuests on the MysticIdentity player portal (2.0 specification §19.1): active quests with
 * their current step and objectives, quest history, and story milestones, all read-only.
 *
 * <p>Nothing a web visit does may write, and nothing it reads is loaded into the game's caches:
 * {@link QuestPortalSource} reads an offline player's progress straight from storage. A read that
 * fails completes exceptionally, which the portal shows as "unavailable", never as an empty
 * quest log. Puzzle selections, hidden objectives and story state beyond milestones are not shown:
 * the portal must not spoil what the game keeps secret.
 */
final class QuestsPortalProvider implements PortalProvider {
    static final String MODULE_ID = "quests";
    static final String VIEW = "quests.view";
    static final String HISTORY_VIEW = "quests.history.view";
    static final String STORY_VIEW = "quests.story.view";
    static final String AREA = "quests";

    private static final int HISTORY_LIMIT = 50;
    private static final int MILESTONE_LIMIT = 50;

    private final QuestPortalSource source;

    QuestsPortalProvider(QuestPortalSource source) {
        this.source = source;
    }

    @Override
    public String moduleId() {
        return MODULE_ID;
    }

    @Override
    public String displayName() {
        return "MysticQuests";
    }

    @Override
    public PortalManifest manifest() {
        return PortalManifest.builder()
                .capability(PortalManifest.Capability.view(VIEW, "Active quests, their current step and objectives."))
                .capability(PortalManifest.Capability.view(HISTORY_VIEW, "Completed quests and when they were finished."))
                .capability(PortalManifest.Capability.view(STORY_VIEW, "Story milestones the player has reached."))
                .area(AREA, "Quests", PortalManifest.Group.MY_GAME, 40,
                        new PortalManifest.Page("active", "Active quests", VIEW),
                        new PortalManifest.Page("history", "Quest history", HISTORY_VIEW),
                        new PortalManifest.Page("story", "Story", STORY_VIEW))
                .widget("tracked", "Current quest", VIEW, 30, PortalManifest.Size.SMALL)
                .card(VIEW, AREA)
                .theme("#9B7BFF", PortalManifest.Theme.Style.STANDARD)
                .build();
    }

    @Override
    public PortalHealth health() {
        return source.available()
                ? PortalHealth.available()
                : PortalHealth.offline("MysticQuests is not running on this server.");
    }

    @Override
    public CompletionStage<PortalView> render(PortalRequest request) {
        UUID player = request.characterId();
        if (player == null) {
            return CompletableFuture.failedFuture(new IllegalArgumentException("no character"));
        }
        Locale locale = request.locale() == Locale.ROOT ? Locale.UK : request.locale();
        try {
            return CompletableFuture.completedFuture(PortalView.of(switch (request.kind() + ":" + request.targetId()) {
                case "WIDGET:tracked" -> tracked(source.journal(player));
                case "CARD:" + AREA -> card(source.journal(player));
                case "PAGE:active" -> active(source.journal(player));
                case "PAGE:history" -> history(source.journal(player), locale);
                case "PAGE:story" -> story(source.milestones(player), locale);
                default -> throw new IllegalArgumentException("no such target: " + request.targetId());
            }));
        } catch (IOException | RuntimeException failure) {
            return CompletableFuture.failedFuture(failure);
        }
    }

    // --- Targets ---

    /** The tracked quest, or the first active one, with what to do next. */
    static List<PortalBlock> tracked(PlayerJournal journal) {
        JournalEntry quest = journal.active().stream()
                .filter(entry -> entry.questId().equals(journal.trackedQuestId()))
                .findFirst()
                .orElse(journal.active().isEmpty() ? null : journal.active().getFirst());
        if (quest == null) {
            return List.of(new PortalBlock.Empty("No active quest", "Pick one up in game from the quest board: /quest"));
        }
        List<PortalBlock> blocks = new ArrayList<>();
        blocks.add(new PortalBlock.Stats(List.of(Stat.of("Quest", quest.displayName()))));
        nextObjective(quest).ifPresent(objective -> blocks.add(new PortalBlock.Progress(
                objective.displayName(), objective.current(), Math.max(1, objective.target()), objective.progressLabel(), Tone.ACCENT)));
        blocks.add(new PortalBlock.Link("All quests", AREA, "active"));
        return blocks;
    }

    static List<PortalBlock> card(PlayerJournal journal) {
        return List.of(new PortalBlock.Stats(List.of(
                Stat.of("Active quests", Integer.toString(journal.active().size())),
                Stat.of("Completed", Integer.toString(journal.completed().size())))));
    }

    /** Each active quest: its step, its objectives in that step, and overall progress. */
    static List<PortalBlock> active(PlayerJournal journal) {
        if (journal.active().isEmpty()) {
            return List.of(new PortalBlock.Empty("No active quests", "Pick one up in game from the quest board: /quest"));
        }
        List<PortalBlock> blocks = new ArrayList<>();
        for (JournalEntry quest : journal.active()) {
            StageView stage = quest.currentStage();
            String step = quest.grouped() && stage != null ? stage.stepLabel() + ": " + stage.displayName() : null;
            List<ObjectiveView> objectives = stage == null ? quest.objectives() : stage.objectives();
            List<Item> rows = objectives.stream()
                    .map(objective -> new Item(objective.displayName(), objective.progressLabel(), null,
                            objective.complete() ? "Done" : null, objective.complete() ? Tone.GOOD : Tone.NEUTRAL))
                    .toList();
            List<PortalBlock> section = new ArrayList<>();
            if (!quest.description().isBlank()) {
                section.add(new PortalBlock.Text(quest.description()));
            }
            section.add(new PortalBlock.Items(rows, "No objectives in this step"));
            section.add(new PortalBlock.Progress("Quest progress", quest.completedObjectiveCount(),
                    Math.max(1, quest.objectives().size()), quest.progressLabel(), Tone.ACCENT));
            boolean tracked = quest.questId().equals(journal.trackedQuestId());
            blocks.add(new PortalBlock.Section(quest.displayName() + (tracked ? " (tracked)" : ""), step, section));
        }
        return blocks;
    }

    static List<PortalBlock> history(PlayerJournal journal, Locale locale) {
        DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneOffset.UTC);
        List<List<String>> rows = journal.completed().stream()
                .limit(HISTORY_LIMIT)
                .map(quest -> List.of(quest.displayName(), date.format(quest.completedAt())))
                .toList();
        List<PortalBlock> blocks = new ArrayList<>();
        blocks.add(new PortalBlock.Table(List.of("Quest", "Completed"), rows, "No quests completed yet"));
        if (journal.completed().size() > HISTORY_LIMIT) {
            blocks.add(new PortalBlock.Text("Showing the " + HISTORY_LIMIT + " most recent of " + journal.completed().size() + "."));
        }
        return blocks;
    }

    static List<PortalBlock> story(List<Milestone> milestones, Locale locale) {
        DateTimeFormatter date = DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).withZone(ZoneOffset.UTC);
        List<Item> items = milestones.stream()
                .limit(MILESTONE_LIMIT)
                .map(milestone -> new Item(milestone.text(), null, date.format(milestone.reached()), null, Tone.ACCENT))
                .toList();
        return List.of(new PortalBlock.Items(items, "No story milestones yet"));
    }

    private static Optional<ObjectiveView> nextObjective(JournalEntry quest) {
        StageView stage = quest.currentStage();
        List<ObjectiveView> objectives = stage == null ? quest.objectives() : stage.objectives();
        return objectives.stream().filter(objective -> !objective.complete()).findFirst();
    }
}
