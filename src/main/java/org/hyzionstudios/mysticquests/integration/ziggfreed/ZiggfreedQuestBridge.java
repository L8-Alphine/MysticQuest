package org.hyzionstudios.mysticquests.integration.ziggfreed;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.external.ExternalObjective;
import org.hyzionstudios.mysticquests.external.ExternalQuest;
import org.hyzionstudios.mysticquests.external.ExternalQuestBook;
import org.hyzionstudios.mysticquests.external.ExternalQuestSnapshot;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge;
import org.hyzionstudios.mysticquests.integration.external.MessageText;

import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.common.objectives.hud.TrackedQuestHud;
import com.ziggfreed.common.progress.ObjectiveDef;
import com.ziggfreed.common.progress.ObjectiveProgressState;
import com.ziggfreed.common.progress.runtime.ProgressionRuntime;
import com.ziggfreed.common.progress.runtime.ProgressionSubjectSource;
import com.ziggfreed.common.progress.runtime.ProgressionTexts;
import com.ziggfreed.common.quest.Quest;
import com.ziggfreed.common.quest.QuestEngine;
import com.ziggfreed.common.quest.QuestStatus;
import com.ziggfreed.common.subject.Subject;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeSet;
import java.util.logging.Level;

/**
 * Mirrors quests run by ZiggfreedCommon's shared quest engine — MMOSkillTree's onboarding and temple
 * lines, and whatever its packs (bounties, mastery, taming, stations) publish to it.
 *
 * <p>Everything is read through ZiggfreedCommon's own runtime: the engine from
 * {@link ProgressionRuntime#quests()}, the player from {@link ProgressionRuntime#subjects()}, and the
 * words from {@link ProgressionTexts}, the same calls its tracked-quest HUD makes
 * ({@code TrackedQuestSnapshot#blockOf}). So a quest reads the same in MysticQuests as in its book,
 * whichever Ziggfreed mod added it.
 *
 * <p>With MMOSkillTree installed, the snapshot also carries skill levels as stats ({@code level.mining},
 * {@code level.total}, {@code level.combat}) for {@code mmo:skillLevel}, and MMOSkillTree's xp events
 * are forwarded as {@code mmo:level_up:<skill>} and {@code mmo:xp:<skill>} signals. That part lives in
 * {@link MmoSkills}, which only loads when MMOSkillTree does.
 */
public final class ZiggfreedQuestBridge implements ExternalQuestBridge {
    static final String ID = "ziggfreed";
    static final String CATEGORY = "mmo";
    private static final String MMO_API = "com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI";

    private final HytaleLogger logger;
    @Nullable
    private final MmoSkills skills;

    public ZiggfreedQuestBridge(HytaleLogger logger) {
        this.logger = logger;
        this.skills = mmoInstalled() ? new MmoSkills(logger) : null;
        if (skills == null) {
            logger.at(Level.INFO).log("MMOSkillTree is not installed; only ZiggfreedCommon quests are mirrored.");
        }
    }

    private static boolean mmoInstalled() {
        try {
            Class.forName(MMO_API, false, ZiggfreedQuestBridge.class.getClassLoader());
            return true;
        } catch (ClassNotFoundException | LinkageError absent) {
            return false;
        }
    }

    @Override
    public String id() {
        return ID;
    }

    @Override
    public String displayName() {
        return skills != null ? "MMO Skill Tree" : "ZiggfreedCommon";
    }

    @Override
    public Set<String> trackerHudKeys() {
        return Set.of(TrackedQuestHud.HUD_KEY);
    }

    @Nullable
    private static QuestEngine engine() {
        return ProgressionRuntime.isBuilt() ? ProgressionRuntime.quests() : null;
    }

    @Nullable
    private static Subject subject(PlayerHandle player) {
        ProgressionSubjectSource subjects = ProgressionRuntime.isBuilt() ? ProgressionRuntime.subjects() : null;
        return subjects == null ? null : subjects.questSubject(player.store(), player.ref());
    }

    @Override
    public ExternalQuestSnapshot snapshot(PlayerHandle player) {
        QuestEngine engine = engine();
        Subject subject = engine == null ? null : subject(player);
        Map<String, Long> stats = skills == null ? Map.of() : skills.levels(player);
        if (subject == null) {
            return new ExternalQuestSnapshot(ID, List.of(), List.of(), Set.of(), stats);
        }
        String language = player.playerRef().getLanguage();
        List<ExternalQuest> active = new ArrayList<>();
        for (Quest quest : engine.activeAndUnclaimed(subject)) {
            active.add(quest(engine, subject, quest, language));
        }
        Set<String> completed = new HashSet<>();
        for (Quest quest : engine.quests()) {
            QuestStatus status = engine.status(subject, quest);
            if (status == QuestStatus.COMPLETED || status == QuestStatus.ON_COOLDOWN) {
                completed.add(quest.id());
            }
        }
        return new ExternalQuestSnapshot(ID, active, List.copyOf(engine.tracked(subject)), completed, stats);
    }

    private static ExternalQuest quest(QuestEngine engine, Subject subject, Quest quest, String language) {
        QuestStatus status = engine.status(subject, quest);
        List<ObjectiveDef> step = engine.activeStepObjectives(subject, quest);
        Map<String, ObjectiveProgressState> progress = engine.progressOf(subject, quest.id());
        List<ExternalObjective> objectives = new ArrayList<>();
        for (ObjectiveDef objective : step) {
            ObjectiveProgressState state = progress == null ? null : progress.get(objective.id());
            int required = state != null && state.required() > 0 ? state.required() : Math.max(1, objective.amountAsInt());
            int current = state == null ? 0 : state.current();
            objectives.add(new ExternalObjective(objective.id(),
                    MessageText.plain(ProgressionTexts.objectiveOrUntitled(quest.id(), objective.id()), language),
                    current, required, state != null && state.isCompleted()));
        }
        // Ordered quests run in steps, one per distinct Order; the current one is the active step's.
        TreeSet<Integer> orders = new TreeSet<>();
        for (ObjectiveDef objective : quest.objectives()) {
            orders.add(objective.order());
        }
        int stepIndex = 1;
        int stepCount = 1;
        if (quest.hasOrderedObjectives() && orders.size() > 1) {
            stepCount = orders.size();
            int current = step.isEmpty() ? orders.last() : step.getFirst().order();
            stepIndex = orders.headSet(current, true).size();
        }
        boolean unclaimed = status == QuestStatus.COMPLETED_UNCLAIMED;
        return new ExternalQuest(ID, quest.id(),
                MessageText.plain(ProgressionTexts.titleOrUntitled(quest.id()), language),
                MessageText.optional(ProgressionTexts.flavor(quest.id()), language),
                CATEGORY, stepIndex, stepCount, "",
                objectives,
                unclaimed ? ExternalQuest.Status.READY : ExternalQuest.Status.ACTIVE,
                unclaimed ? "Claim your reward in the quest book" : "",
                "");
    }

    @Override
    public boolean track(PlayerHandle player, String questId) {
        QuestEngine engine = engine();
        Subject subject = engine == null ? null : subject(player);
        return subject != null && engine.track(subject, questId);
    }

    @Override
    public boolean untrack(PlayerHandle player, String questId) {
        QuestEngine engine = engine();
        Subject subject = engine == null ? null : subject(player);
        return subject != null && engine.untrack(subject, questId);
    }

    @Override
    public boolean abandon(PlayerHandle player, String questId) {
        QuestEngine engine = engine();
        Subject subject = engine == null ? null : subject(player);
        return subject != null && engine.abandon(subject, questId);
    }

    /** Through the engine's own acceptance check: prerequisites, log space and cooldowns still apply. */
    @Override
    public boolean accept(PlayerHandle player, String questId) {
        QuestEngine engine = engine();
        Subject subject = engine == null ? null : subject(player);
        Quest quest = engine == null ? null : engine.quest(questId);
        return subject != null && quest != null && engine.accept(subject, quest);
    }

    @Override
    public Set<String> registerContent(MysticQuestsRegistry registry, ExternalQuestBook book, OnWorld onWorld) {
        return skills == null ? Set.of() : skills.registerContent(registry, book, onWorld);
    }

    @Override
    public void forwardSignals(SignalSink signals) {
        if (skills != null) {
            skills.forwardSignals(signals);
        }
    }

    @Override
    public Map<String, Integer> counts() {
        QuestEngine engine = engine();
        if (engine == null) {
            return Map.of();
        }
        Map<String, Integer> counts = new HashMap<>();
        counts.put("quests", engine.quests().size());
        return counts;
    }

    @Override
    public void close() {
        if (skills != null) {
            skills.close();
        }
    }
}
