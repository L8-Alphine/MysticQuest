package org.hyzionstudios.mysticquests.integration.ziggfreed;

import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.external.ExternalQuestBook;
import org.hyzionstudios.mysticquests.external.ExternalSignals;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge.OnWorld;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge.PlayerHandle;
import org.hyzionstudios.mysticquests.integration.external.ExternalQuestBridge.SignalSink;

import com.hypixel.hytale.logger.HytaleLogger;
import com.ziggfreed.mmoskilltree.api.MMOSkillTreeAPI;
import com.ziggfreed.mmoskilltree.api.events.PlayerGainXpEvent;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * MMOSkillTree's skill levels for MysticQuests content, through its public {@link MMOSkillTreeAPI}.
 * Kept apart from {@link ZiggfreedQuestBridge} so a server running ZiggfreedCommon without
 * MMOSkillTree never loads a class that names MMOSkillTree's API.
 *
 * <p>Content it adds:
 * <pre>
 *   condition  mmo:skillLevel   { "skill": "MINING", "level": 20 }   a skill id, "total" or "combat"
 *   event      mmo:addXp        { "skill": "MINING", "amount": 500 } through MMOSkillTree, boosts and caps included
 *   signals    mmo:level_up:&lt;SKILL&gt;   mmo:level_up    (amount = levels gained)
 *              mmo:xp:&lt;SKILL&gt;         mmo:xp          (amount = xp gained)
 * </pre>
 */
final class MmoSkills {
    static final String TOTAL = "total";
    static final String COMBAT = "combat";

    private final HytaleLogger logger;
    @Nullable
    private Consumer<PlayerGainXpEvent> xpListener;

    MmoSkills(HytaleLogger logger) {
        this.logger = logger;
    }

    /** The stat key a skill's level is filed under: {@code level.mining}, {@code level.total}. */
    static String levelKey(String skill) {
        return "level." + skill.strip().toLowerCase(Locale.ROOT);
    }

    /** Every skill level of the player, plus total and combat level; empty before MMOSkillTree has their data. */
    Map<String, Long> levels(PlayerHandle player) {
        if (!MMOSkillTreeAPI.hasSkillData(player.store(), player.ref())) {
            return Map.of();
        }
        Map<String, Long> levels = new LinkedHashMap<>();
        MMOSkillTreeAPI.getAllLevelsByName(player.store(), player.ref())
                .forEach((skill, level) -> levels.put(levelKey(skill), level.longValue()));
        levels.put(levelKey(TOTAL), (long) MMOSkillTreeAPI.getTotalLevel(player.store(), player.ref()));
        levels.put(levelKey(COMBAT), (long) MMOSkillTreeAPI.getCombatLevel(player.store(), player.ref()));
        return levels;
    }

    Set<String> registerContent(MysticQuestsRegistry registry, ExternalQuestBook book, OnWorld onWorld) {
        Set<String> registered = new LinkedHashSet<>();
        if (registry.registerCondition("mmo:skillLevel", context -> {
            String skill = context.text("skill", TOTAL);
            long level = context.definition().longValue("level", 1);
            return book.stat(context.playerId(), ZiggfreedQuestBridge.ID, levelKey(skill)).orElse(0) >= level;
        })) {
            registered.add("mmo:skillLevel");
        }
        if (registry.registerEvent("mmo:addXp", context -> {
            String skill = context.text("skill", "");
            long amount = context.definition().longValue("amount", 0);
            if (skill.isBlank() || amount <= 0) {
                return;
            }
            onWorld.run(context.playerId(), player -> addXp(player, skill, amount));
        })) {
            registered.add("mmo:addXp");
        }
        return registered;
    }

    /** Adds xp under MMOSkillTree's own spelling of the skill, whatever case the author used. */
    private void addXp(PlayerHandle player, String skill, long amount) {
        String id = skill;
        for (String known : MMOSkillTreeAPI.getAllLevelsByName(player.store(), player.ref()).keySet()) {
            if (known.equalsIgnoreCase(skill)) {
                id = known;
                break;
            }
        }
        if (!MMOSkillTreeAPI.addXp(player.store(), player.ref(), id, amount)) {
            logger.at(Level.WARNING).log("mmo:addXp: MMOSkillTree did not add " + amount + " xp to '" + skill + "'.");
        }
    }

    void forwardSignals(SignalSink signals) {
        Consumer<PlayerGainXpEvent> listener = event -> {
            if (event.getPlayerRef() == null || event.getSkillId() == null) {
                return;
            }
            UUID player = event.getPlayerRef().getUuid();
            // Skills are named as MMOSkillTree's own files name them, MINING, whatever the event carries.
            String skill = event.getSkillId().toUpperCase(Locale.ROOT);
            List<ExternalSignals.Signal> out = new ArrayList<>();
            ExternalSignals.pair(out, "mmo:xp", skill, (int) Math.min(Integer.MAX_VALUE, event.getFinalAmount()));
            if (event.isLevelUp()) {
                ExternalSignals.pair(out, "mmo:level_up", skill, Math.max(1, event.getLevelAfter() - event.getLevelBefore()));
            }
            for (ExternalSignals.Signal signal : out) {
                signals.publish(player, signal.name(), signal.amount());
            }
        };
        MMOSkillTreeAPI.addXpListener(listener);
        xpListener = listener;
    }

    void close() {
        if (xpListener != null) {
            MMOSkillTreeAPI.removeXpListener(xpListener);
            xpListener = null;
        }
    }
}
