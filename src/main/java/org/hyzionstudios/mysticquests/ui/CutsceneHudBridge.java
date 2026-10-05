package org.hyzionstudios.mysticquests.ui;

import org.hyzionstudios.mysticquests.narrative.cutscene.QuestCutsceneService;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import java.util.HashSet;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Hides MysticQuests' HUD layers while a story cutscene plays (Redesign Bible §6.5: a scene owns the
 * screen), the same way an open conversation does.
 *
 * <p>Whoever was hidden when the scene started is restored when it ends, together with anyone who
 * joined the session's audience meanwhile, so a party member who left mid-scene does not keep a
 * hidden HUD.
 */
public final class CutsceneHudBridge implements QuestCutsceneService.Listener {
    private final QuestHudService hud;
    private final Map<String, Set<UUID>> hidden = new ConcurrentHashMap<>();

    public CutsceneHudBridge(QuestHudService hud) {
        this.hud = hud;
    }

    @Override
    public void started(NamespacedId cutscene, String sessionId, Set<UUID> audience) {
        hidden.put(sessionId, Set.copyOf(audience));
        for (UUID player : audience) {
            hud.setCinematic(player, source(sessionId), true);
        }
    }

    @Override
    public void ended(NamespacedId cutscene, String sessionId, Set<UUID> audience) {
        Set<UUID> restore = new HashSet<>(audience);
        Set<UUID> previously = hidden.remove(sessionId);
        if (previously != null) {
            restore.addAll(previously);
        }
        for (UUID player : restore) {
            hud.setCinematic(player, source(sessionId), false);
        }
    }

    static String source(String sessionId) {
        return "cutscene:" + sessionId;
    }
}
