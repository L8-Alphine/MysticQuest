package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.service.JournalEntry;
import org.hyzionstudios.mysticquests.service.StageView;

import javax.annotation.Nullable;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the Quest Journal shows (Redesign Bible §7.1-7.2), worked out without any UI: the rail's
 * sections in order, which entry is selected, and each quest's timeline. The page only draws this.
 *
 * <p>The rail leads with the tracked quest, then active quests by category, the player's stories,
 * and finished quests, newest first. A selection survives re-renders; with none, the Journal opens on
 * the tracked quest, so opening it from the HUD lands on the quest the HUD was showing.
 */
public final class JournalModel {
    static final int COMPLETED_SHOWN = 25;
    static final int ABANDONED_SHOWN = 10;
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("d MMM yyyy", Locale.ENGLISH).withZone(ZoneOffset.UTC);

    public enum Kind { QUEST, STORY, SETTINGS }

    public enum State { TRACKED, ACTIVE, COMPLETE, ABANDONED, STORY }

    /** One finished quest and when it finished. */
    public record Finished(JournalEntry entry, @Nullable Instant at) {
    }

    /** One row in the rail. */
    public record Item(Kind kind, String id, String title, String subtitle, State state) {
    }

    public record Section(String title, List<Item> items) {
    }

    /** A selected quest, with what the detail pane shows about it. */
    public record QuestDetail(JournalEntry entry, State state, String category, String rewardText,
                              List<String> timeline, @Nullable String when) {
    }

    private final List<Section> sections;
    private final Map<String, JournalEntry> quests = new LinkedHashMap<>();
    private final Map<String, State> states = new LinkedHashMap<>();
    private final Map<String, Instant> started = new LinkedHashMap<>();
    private final Map<String, Instant> finished = new LinkedHashMap<>();
    private final Map<String, JournalSources.Story> stories = new LinkedHashMap<>();
    private final Function<String, String> categoryOf;
    private final Function<String, String> rewardTextOf;

    /**
     * @param active active quests, in the order the player took them
     * @param startedAt when each active quest was accepted
     * @param categoryOf a quest's category, empty when it has none
     * @param rewardTextOf a quest's revealed rewards, empty when it reveals none
     */
    public JournalModel(List<JournalEntry> active, @Nullable String trackedId, Map<String, Instant> startedAt,
                        List<Finished> completed, List<Finished> abandoned, List<JournalSources.Story> stories,
                        Function<String, String> categoryOf, Function<String, String> rewardTextOf) {
        this.categoryOf = categoryOf;
        this.rewardTextOf = rewardTextOf;
        this.started.putAll(startedAt);
        List<Section> built = new ArrayList<>();

        JournalEntry tracked = active.stream().filter(entry -> entry.questId().equals(trackedId)).findFirst().orElse(null);
        if (tracked != null) {
            built.add(new Section("TRACKED", List.of(questItem(tracked, State.TRACKED))));
        }
        Map<String, List<Item>> byCategory = new LinkedHashMap<>();
        active.stream()
                .filter(entry -> entry != tracked)
                .sorted(Comparator.comparingInt(entry -> QuestCategories.rank(categoryOf.apply(entry.questId()))))
                .forEach(entry -> byCategory.computeIfAbsent(categoryOf.apply(entry.questId()), ignored -> new ArrayList<>())
                        .add(questItem(entry, State.ACTIVE)));
        byCategory.forEach((category, items) -> built.add(new Section(QuestCategories.section(category), items)));

        if (!stories.isEmpty()) {
            List<Item> items = new ArrayList<>();
            for (JournalSources.Story story : stories) {
                this.stories.put(story.key(), story);
                items.add(new Item(Kind.STORY, story.key(), story.name(),
                        story.party() ? "Party story" : "Your story", State.STORY));
            }
            built.add(new Section("STORIES", items));
        }
        built.add(finishedSection("COMPLETED", completed, State.COMPLETE, COMPLETED_SHOWN));
        built.add(finishedSection("ABANDONED", abandoned, State.ABANDONED, ABANDONED_SHOWN));
        built.removeIf(section -> section.items().isEmpty());
        this.sections = List.copyOf(built);
    }

    private Item questItem(JournalEntry entry, State state) {
        quests.putIfAbsent(entry.questId(), entry);
        states.putIfAbsent(entry.questId(), state);
        String subtitle = switch (state) {
            case COMPLETE -> "Completed" + date(finished.get(entry.questId()), " ");
            case ABANDONED -> "Abandoned" + date(finished.get(entry.questId()), " ");
            default -> entry.progressSummary();
        };
        return new Item(Kind.QUEST, entry.questId(), entry.displayName(), subtitle, state);
    }

    private Section finishedSection(String title, List<Finished> entries, State state, int limit) {
        List<Item> items = new ArrayList<>();
        entries.stream()
                .sorted(Comparator.comparing((Finished entry) -> entry.at() == null ? Instant.EPOCH : entry.at()).reversed())
                .filter(entry -> !quests.containsKey(entry.entry().questId()))
                .limit(limit)
                .forEach(entry -> {
                    if (entry.at() != null) {
                        finished.put(entry.entry().questId(), entry.at());
                    }
                    items.add(questItem(entry.entry(), state));
                });
        return new Section(title, items);
    }

    public List<Section> sections() {
        return sections;
    }

    public int storyCount() {
        return stories.size();
    }

    /**
     * The entry to show: the requested one if it is still in the rail, else the tracked quest, the
     * first active quest, then anything at all; empty when the Journal has nothing.
     */
    public Optional<Item> select(@Nullable Kind kind, @Nullable String id) {
        List<Item> all = sections.stream().flatMap(section -> section.items().stream()).toList();
        if (kind != null && id != null) {
            Optional<Item> requested = all.stream().filter(item -> item.kind() == kind && item.id().equals(id)).findFirst();
            if (requested.isPresent()) {
                return requested;
            }
        }
        return all.stream().filter(item -> item.state() == State.TRACKED).findFirst()
                .or(() -> all.stream().filter(item -> item.state() == State.ACTIVE).findFirst())
                .or(() -> all.stream().findFirst());
    }

    public Optional<JournalSources.Story> story(String key) {
        return Optional.ofNullable(stories.get(key));
    }

    public Optional<QuestDetail> quest(String questId) {
        JournalEntry entry = quests.get(questId);
        if (entry == null) {
            return Optional.empty();
        }
        State state = states.get(questId);
        List<String> timeline = new ArrayList<>();
        Instant accepted = started.get(questId);
        if (accepted != null) {
            timeline.add("Accepted on " + DATE.format(accepted));
        }
        StageView stage = entry.currentStage();
        if ((state == State.ACTIVE || state == State.TRACKED) && entry.grouped() && stage != null) {
            timeline.add("Now on " + stage.stepLabel().toLowerCase(Locale.ROOT) + ": " + stage.displayName());
        }
        Instant ended = finished.get(questId);
        if (state == State.COMPLETE) {
            timeline.add(ended == null ? "Completed" : "Completed on " + DATE.format(ended));
        } else if (state == State.ABANDONED) {
            timeline.add(ended == null ? "Abandoned" : "Abandoned on " + DATE.format(ended));
        }
        String when = switch (state) {
            case COMPLETE, ABANDONED -> ended == null ? null : DATE.format(ended);
            default -> accepted == null ? null : "Since " + DATE.format(accepted);
        };
        return Optional.of(new QuestDetail(entry, state, categoryOf.apply(questId), rewardTextOf.apply(questId), timeline, when));
    }

    static String date(@Nullable Instant at, String prefix) {
        return at == null ? "" : prefix + DATE.format(at);
    }
}
