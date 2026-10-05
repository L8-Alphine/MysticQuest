package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.model.QuestDefinition;
import org.hyzionstudios.mysticquests.service.JournalEntry;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Function;

/**
 * What the quest board shows (Redesign Bible §7.3): available quests grouped by category, then the
 * locked quests whose authors wrote a public requirement. Each card separates difficulty, party
 * size, availability and the rewards the author reveals, and only what the author provided.
 */
public final class QuestBoardModel {
    /** One quest on the board. */
    public record Card(JournalEntry entry, boolean locked, String category, String difficulty, int partySize,
                       String rewardText, String lockedText) {
        /** The card's one-line summary: objectives, then difficulty and party size when given. */
        public String meta() {
            List<String> parts = new ArrayList<>();
            int objectives = entry.objectives().size();
            parts.add(objectives == 1 ? "1 objective" : objectives + " objectives");
            if (!difficulty.isEmpty()) {
                parts.add(difficulty);
            }
            if (partySize > 0) {
                parts.add("Party of " + partySize);
            }
            return String.join("  |  ", parts);
        }

        /** Labelled facts for the preview pane, in a fixed order. */
        public List<Fact> facts() {
            List<Fact> facts = new ArrayList<>();
            if (!difficulty.isEmpty()) {
                facts.add(new Fact("DIFFICULTY", difficulty));
            }
            facts.add(new Fact("PARTY", partySize > 0 ? "Best with " + partySize + " players" : "Solo"));
            int steps = entry.grouped() ? entry.stages().size() : 0;
            int objectives = entry.objectives().size();
            facts.add(new Fact("OBJECTIVES", (objectives == 1 ? "1 objective" : objectives + " objectives")
                    + (steps > 1 ? " in " + steps + " steps" : "")));
            if (!rewardText.isEmpty()) {
                facts.add(new Fact("REWARDS", rewardText));
            }
            return facts;
        }
    }

    public record Fact(String label, String value) {
    }

    public record Section(String title, List<Card> cards) {
    }

    private final List<Section> sections;
    private final int available;

    public QuestBoardModel(List<JournalEntry> availableQuests, List<JournalEntry> lockedQuests,
                           Function<String, Optional<QuestDefinition>> definitions) {
        List<Section> built = new ArrayList<>();
        Map<String, List<Card>> byCategory = new LinkedHashMap<>();
        availableQuests.stream()
                .map(entry -> card(entry, false, definitions))
                .sorted(Comparator.comparingInt(card -> QuestCategories.rank(card.category())))
                .forEach(card -> byCategory.computeIfAbsent(card.category(), ignored -> new ArrayList<>()).add(card));
        byCategory.forEach((category, cards) -> built.add(new Section(QuestCategories.section(category), cards)));
        List<Card> locked = lockedQuests.stream().map(entry -> card(entry, true, definitions)).toList();
        if (!locked.isEmpty()) {
            built.add(new Section("NOT YET AVAILABLE", locked));
        }
        this.sections = List.copyOf(built);
        this.available = availableQuests.size();
    }

    private static Card card(JournalEntry entry, boolean locked, Function<String, Optional<QuestDefinition>> definitions) {
        Optional<QuestDefinition> definition = definitions.apply(entry.questId());
        return new Card(entry, locked,
                definition.map(QuestDefinition::category).orElse(""),
                definition.map(QuestDefinition::difficulty).orElse(""),
                definition.map(QuestDefinition::partySize).orElse(0),
                definition.map(QuestDefinition::rewardText).orElse(""),
                definition.map(QuestDefinition::lockedText).orElse(""));
    }

    public List<Section> sections() {
        return sections;
    }

    public int availableCount() {
        return available;
    }

    public boolean isEmpty() {
        return sections.isEmpty();
    }

    /** The requested card if it is still on the board, else the first available one, else the first locked one. */
    public Optional<Card> select(@Nullable String questId) {
        List<Card> all = sections.stream().flatMap(section -> section.cards().stream()).toList();
        return all.stream().filter(card -> card.entry().questId().equals(questId)).findFirst()
                .or(() -> all.stream().filter(card -> !card.locked()).findFirst())
                .or(() -> all.stream().findFirst());
    }
}
