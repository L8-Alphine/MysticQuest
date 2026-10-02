package org.hyzionstudios.mysticquests.service;

import org.hyzionstudios.mysticquests.event.MysticQuestsEventBus;
import org.hyzionstudios.mysticquests.event.StateEvents;
import org.hyzionstudios.mysticquests.integration.MysticVanishBridge;
import org.hyzionstudios.mysticquests.narrative.PresentationLayer;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.entity.entities.player.HiddenPlayersManager;
import com.hypixel.hytale.server.core.modules.entity.EntityModule;
import com.hypixel.hytale.server.core.modules.entity.player.PlayerSettings;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.logging.Level;

/**
 * Per-viewer visibility overrides for both players and non-player entities.
 *
 * <p>Three different mechanisms sit behind one set of hidden UUIDs, because the engine only
 * implements part of what is needed:
 *
 * <ul>
 *   <li><b>Players</b> go through {@link HiddenPlayersManager}. The engine's own
 *       {@code EntityTrackerSystems$HideFromPlayer} runs each tick, drops hidden players out of the
 *       viewer's visible set before packets are generated, and so produces a correct despawn — and a
 *       correct respawn once unhidden.</li>
 *   <li><b>Non-player entities</b> are skipped by that system: it only processes refs whose archetype
 *       contains a {@code PlayerRef}. {@link org.hyzionstudios.mysticquests.hytale.EntityVisibilitySystem}
 *       mirrors it for everything else, reading the same hidden sets kept here.</li>
 *   <li><b>Nameplates</b> ride a separate path that neither of the above touches, so
 *       {@link org.hyzionstudios.mysticquests.hytale.NameplateVisibilitySystem} filters the
 *       subject-side viewer map as well.</li>
 * </ul>
 *
 * <h2>Sharing {@code HiddenPlayersManager} with other mods</h2>
 *
 * <p>{@link HiddenPlayersManager} is a single per-viewer {@code Set<UUID>} owned by the engine, with
 * no notion of who asked for an entry. MysticVanish hides players through that same set. A naive
 * {@code showPlayer} therefore cancels whatever another mod wanted, which is how releasing a quest
 * hide could un-vanish an admin.
 *
 * <p>Two rules keep the mods out of each other's way:
 *
 * <ul>
 *   <li><b>Never lift a hide we did not place.</b> Each hold records whether it was the one to put the
 *       UUID into the engine set; a hold that found it already hidden leaves it alone on release. A
 *       release additionally defers to {@link MysticVanishBridge}, so a player who is still vanished
 *       stays hidden even if MysticQuests placed the entry first.</li>
 *   <li><b>Re-assert our own hides.</b> {@link #enforcePlayerHides} runs each tick from the visibility
 *       system and re-adds anything that was cleared from under us, so a mod that drops the whole set
 *       costs one tick of visibility rather than permanently defeating a quest.</li>
 * </ul>
 *
 * <p>A hide is attributed to a {@link Source}, and a subject stays hidden while <em>any</em> source
 * wants it hidden. Without that, a condition-driven rule releasing its hold would also cancel a hide
 * a quest event had explicitly applied, and the two features would silently undo each other.
 *
 * <p>State is runtime-only and cleared on disconnect: a hide is a scene effect, not a save-game fact.
 *
 * <p>One engine caveat is surfaced rather than silently tolerated — a viewer with the "show entity
 * markers" client setting enabled makes {@code HideFromPlayer} return early, so hiding does nothing
 * for them. That is reported once per viewer instead of looking like a bug in the quest.
 */
public final class VisibilityService {
    /** Who asked for a hide. A subject is hidden while at least one source still wants it. */
    public enum Source {
        /** An explicit {@code hidePlayer} / {@code hideEntity} quest event or volume effect. */
        EVENT,
        /** A condition-driven visibility rule from package content. */
        RULE
    }

    private final PlayerSessionService sessions;
    private final MysticQuestsEventBus eventBus;
    private final HytaleLogger logger;

    @Nullable
    private final MysticVanishBridge vanish;

    private final Map<UUID, Map<UUID, Hold>> hiddenByViewer = new ConcurrentHashMap<>();

    /**
     * Number of viewer/subject pairs currently hidden. The entity visibility system reads this every
     * tick for every viewer, so it must not require touching the maps.
     */
    private final AtomicInteger activeHides = new AtomicInteger();

    /** Viewers already warned about the entity-markers setting, so the log is not spammed. */
    private final Set<UUID> warnedAboutMarkers = ConcurrentHashMap.newKeySet();

    /** Staff currently in presentation bypass; see {@link #setBypass}. */
    private final Set<UUID> bypassing = ConcurrentHashMap.newKeySet();

    /** The subset of {@link #bypassing} held on by the always-bypass permission. */
    private final Set<UUID> forcedBypass = ConcurrentHashMap.newKeySet();

    /**
     * Per-viewer presentation layers from the narrative runtime: story entities (§8) and world overlay
     * barriers (§9). Added once the runtime exists; until then there are none.
     */
    private final List<PresentationLayer> layers = new CopyOnWriteArrayList<>();

    public void addPresentationLayer(PresentationLayer layer) {
        if (layer != null) {
            layers.add(layer);
        }
    }

    private boolean layersEmpty() {
        for (PresentationLayer layer : layers) {
            if (!layer.isEmpty()) {
                return false;
            }
        }
        return true;
    }

    public VisibilityService(
            PlayerSessionService sessions,
            MysticQuestsEventBus eventBus,
            @Nullable HytaleLogger logger) {
        this(sessions, eventBus, logger, null);
    }

    public VisibilityService(
            PlayerSessionService sessions,
            MysticQuestsEventBus eventBus,
            @Nullable HytaleLogger logger,
            @Nullable MysticVanishBridge vanish) {
        this.sessions = sessions;
        this.eventBus = eventBus;
        this.logger = logger;
        this.vanish = vanish;
    }

    /** Hides {@code target} from {@code viewer} on behalf of an explicit quest event. */
    public boolean hide(UUID viewer, UUID target) {
        return hide(viewer, target, Source.EVENT);
    }

    /** Stops an explicit quest event's hide of {@code target} from {@code viewer}. */
    public boolean show(UUID viewer, UUID target) {
        return show(viewer, target, Source.EVENT);
    }

    /**
     * Hides {@code target} from {@code viewer} on behalf of {@code source}.
     *
     * @return true when the subject went from visible to hidden; false when it was already hidden,
     *         including by a different source
     */
    public boolean hide(UUID viewer, UUID target, Source source) {
        if (viewer == null || target == null || viewer.equals(target)) {
            return false;
        }
        Hold[] created = {null};
        hiddenByViewer
                .computeIfAbsent(viewer, ignored -> new ConcurrentHashMap<>())
                .compute(target, (ignored, hold) -> {
                    if (hold == null) {
                        Hold fresh = new Hold(source);
                        created[0] = fresh;
                        return fresh;
                    }
                    hold.sources.add(source);
                    return hold;
                });
        if (created[0] == null) {
            return false;
        }
        activeHides.incrementAndGet();
        if (!bypassing.contains(viewer)) {
            acquireEngineHide(viewer, target, created[0]);
        }
        eventBus.post(new StateEvents.VisibilityChange(viewer, target, true));
        return true;
    }

    /**
     * Releases {@code source}'s hold on hiding {@code target} from {@code viewer}.
     *
     * @return true when the subject became visible again; false when another source still hides it
     */
    public boolean show(UUID viewer, UUID target, Source source) {
        if (viewer == null || target == null) {
            return false;
        }
        Map<UUID, Hold> hidden = hiddenByViewer.get(viewer);
        if (hidden == null) {
            return false;
        }
        Hold[] released = {null};
        hidden.computeIfPresent(target, (ignored, hold) -> {
            hold.sources.remove(source);
            if (hold.sources.isEmpty()) {
                released[0] = hold;
                return null;
            }
            return hold;
        });
        if (released[0] == null) {
            return false;
        }
        activeHides.decrementAndGet();
        releaseEngineHide(viewer, target, released[0]);
        eventBus.post(new StateEvents.VisibilityChange(viewer, target, false));
        return true;
    }

    public boolean isHidden(UUID viewer, UUID target) {
        Map<UUID, Hold> hidden = viewer == null ? null : hiddenByViewer.get(viewer);
        return hidden != null && hidden.containsKey(target);
    }

    /**
     * Every reason this subject is hidden from this viewer: MysticQuests' own sources, plus
     * {@code VANISH} when MysticVanish also wants it hidden (§6.2, layered visibility). For status
     * output; the story only ever reads its own sources.
     */
    public List<String> reasons(UUID viewer, UUID target) {
        List<String> reasons = new ArrayList<>();
        Map<UUID, Hold> hidden = viewer == null ? null : hiddenByViewer.get(viewer);
        Hold hold = hidden == null ? null : hidden.get(target);
        if (hold != null) {
            hold.sources.forEach(source -> reasons.add(source.name()));
        }
        if (vanish != null && viewer != null && target != null && vanish.wantsHidden(viewer, target)) {
            reasons.add("VANISH");
        }
        return reasons;
    }

    /** True when a specific source is holding this subject hidden. */
    public boolean isHiddenBy(UUID viewer, UUID target, Source source) {
        Map<UUID, Hold> hidden = viewer == null ? null : hiddenByViewer.get(viewer);
        Hold hold = hidden == null ? null : hidden.get(target);
        return hold != null && hold.sources.contains(source);
    }

    /**
     * True when no hide is active anywhere. The per-tick visibility systems call this first so an
     * ordinary server pays a single atomic read rather than a map lookup per viewer per tick.
     */
    public boolean isEmpty() {
        return activeHides.get() == 0 && layersEmpty();
    }

    /** The UUIDs hidden from one viewer. Returns a live view; callers must not modify it. */
    public Set<UUID> hiddenFrom(UUID viewer) {
        Map<UUID, Hold> hidden = viewer == null ? null : hiddenByViewer.get(viewer);
        return hidden == null ? Set.of() : hidden.keySet();
    }

    // --- Staff bypass ---

    /** Lets a staff member switch presentation bypass on and off. */
    public static final String BYPASS_PERMISSION = "mysticquests.visibility.bypass";

    /** Holds bypass on from join, for roles such as owner or developer. */
    public static final String BYPASS_ALWAYS_PERMISSION = "mysticquests.visibility.bypass.always";

    //
    // Bypass is presentation only (§6 of the 2.0 specification). A bypassing viewer is shown everything
    // MysticQuests would hide from them, but every hold stays exactly as it was: isHidden, hiddenFrom,
    // isHiddenBy and the quest conditions built on them keep answering with the story's policy, no
    // VisibilityChange is posted, and switching bypass off puts the hides straight back. Hides owned by
    // another system are never lifted: the release path still defers to MysticVanish.

    /**
     * What the viewer's client should currently be denied: {@link #hiddenFrom} unless they are in
     * bypass. The presentation systems read this; quest logic must keep using {@link #hiddenFrom}.
     */
    public Set<UUID> presentedHiddenFrom(UUID viewer) {
        if (viewer == null || bypassing.contains(viewer)) {
            return Set.of();
        }
        Set<UUID> quest = hiddenFrom(viewer);
        Set<UUID> union = null;
        for (PresentationLayer layer : layers) {
            if (layer.isEmpty()) {
                continue;
            }
            Set<UUID> hidden = layer.hiddenFrom(viewer);
            if (hidden.isEmpty()) {
                continue;
            }
            if (union == null) {
                union = new HashSet<>(quest);
            }
            union.addAll(hidden);
        }
        return union == null ? quest : union;
    }

    /** Presentation counterpart of {@link #isHidden}; also applies every presentation layer. */
    public boolean isPresentedHidden(UUID viewer, UUID target) {
        if (viewer == null || bypassing.contains(viewer)) {
            return false;
        }
        if (isHidden(viewer, target)) {
            return true;
        }
        for (PresentationLayer layer : layers) {
            if (!layer.isEmpty() && layer.hides(viewer, target)) {
                return true;
            }
        }
        return false;
    }

    /**
     * Whether MysticQuests lets {@code viewer} be shown {@code target}; what nameplate mods consult.
     * Says nothing about other systems' hides, which those mods check separately.
     */
    public boolean canSee(UUID viewer, UUID target) {
        return viewer == null || target == null || viewer.equals(target) || !isPresentedHidden(viewer, target);
    }

    public boolean isBypassing(UUID viewer) {
        return viewer != null && bypassing.contains(viewer);
    }

    /** True when bypass is held on by the always-bypass permission and cannot be switched off. */
    public boolean isBypassForced(UUID viewer) {
        return viewer != null && forcedBypass.contains(viewer);
    }

    /**
     * Switches a viewer's presentation bypass.
     *
     * @return false when nothing changed, including a refusal to switch off a forced bypass
     */
    public boolean setBypass(UUID viewer, boolean enabled) {
        if (viewer == null) {
            return false;
        }
        if (!enabled && forcedBypass.contains(viewer)) {
            return false;
        }
        boolean changed = enabled ? bypassing.add(viewer) : bypassing.remove(viewer);
        if (!changed) {
            return false;
        }
        Map<UUID, Hold> hidden = hiddenByViewer.get(viewer);
        if (hidden != null) {
            hidden.forEach((target, hold) -> {
                if (enabled) {
                    releaseEngineHide(viewer, target, hold);
                } else {
                    acquireEngineHide(viewer, target, hold);
                }
            });
        }
        eventBus.post(new StateEvents.VisibilityBypassChange(viewer, enabled));
        return true;
    }

    /**
     * Applies or lifts the always-bypass permission's hold. Forcing also switches bypass on; lifting
     * the force leaves bypass on until the viewer turns it off, so losing the permission mid-session
     * never makes hidden players pop out of view without the staff member asking.
     */
    public void setBypassForced(UUID viewer, boolean forced) {
        if (viewer == null) {
            return;
        }
        if (forced) {
            forcedBypass.add(viewer);
            setBypass(viewer, true);
        } else {
            forcedBypass.remove(viewer);
        }
    }

    /** Restores everything a viewer had hidden, from every source. */
    public void clearViewer(UUID viewer) {
        Map<UUID, Hold> hidden = hiddenByViewer.remove(viewer);
        if (hidden == null) {
            return;
        }
        hidden.forEach((target, hold) -> {
            activeHides.decrementAndGet();
            releaseEngineHide(viewer, target, hold);
            eventBus.post(new StateEvents.VisibilityChange(viewer, target, false));
        });
        warnedAboutMarkers.remove(viewer);
    }

    /**
     * Forgets a departing viewer's bypass. Bypass is a session choice: on their next join only the
     * always-bypass permission turns it back on.
     */
    public void forgetBypass(UUID viewer) {
        forcedBypass.remove(viewer);
        bypassing.remove(viewer);
    }

    /**
     * Removes a player as both viewer and subject. Called on disconnect so a departing player does
     * not leave stale hides pointing at them.
     *
     * <p>The subject half has to push the release onto the engine, not merely forget it. Dropping the
     * map entry alone leaves the departing player's UUID sitting in every other viewer's
     * {@link HiddenPlayersManager}, where nothing will ever remove it: MysticQuests no longer has a
     * record to release, and the engine set survives the subject's reconnect. The player comes back
     * permanently invisible to everyone who had them hidden, in both directions once each side has
     * cycled, and only a relog of every viewer clears it.
     */
    public void clearPlayer(UUID player) {
        clearViewer(player);
        hiddenByViewer.forEach((viewer, hidden) -> {
            Hold hold = hidden.remove(player);
            if (hold != null) {
                activeHides.decrementAndGet();
                releaseEngineHide(viewer, player, hold);
                eventBus.post(new StateEvents.VisibilityChange(viewer, player, false));
            }
        });
    }

    /** Drops every override, restoring normal visibility. Used on content reload and shutdown. */
    public void clearAll() {
        // Bypass deliberately survives: a content reload drops the story's hides, not staff choices.
        for (UUID viewer : Set.copyOf(hiddenByViewer.keySet())) {
            clearViewer(viewer);
        }
        hiddenByViewer.clear();
        activeHides.set(0);
        warnedAboutMarkers.clear();
    }

    /**
     * Re-applies this viewer's player hides to the engine, adding back any that were cleared by
     * something other than MysticQuests.
     *
     * <p>Called once per viewer per tick from
     * {@link org.hyzionstudios.mysticquests.hytale.EntityVisibilitySystem}, already on the world
     * thread that owns {@code viewerRef}. Only ever adds: removal stays with the explicit release
     * path, which knows whether the hold is ours to lift.
     *
     * @return how many hides had to be restored, which is zero on virtually every tick
     */
    public int enforcePlayerHides(PlayerRef viewerRef) {
        if (viewerRef == null || bypassing.contains(viewerRef.getUuid())) {
            return 0;
        }
        Map<UUID, Hold> hidden = hiddenByViewer.get(viewerRef.getUuid());
        if (hidden == null || hidden.isEmpty()) {
            return 0;
        }
        HiddenPlayersManager manager = viewerRef.getHiddenPlayersManager();
        if (manager == null) {
            return 0;
        }
        int restored = 0;
        for (Map.Entry<UUID, Hold> entry : hidden.entrySet()) {
            UUID target = entry.getKey();
            if (!isOnlinePlayer(target) || manager.isPlayerHidden(target)) {
                continue;
            }
            manager.hidePlayer(target);
            entry.getValue().ownsEngineHide = true;
            restored++;
        }
        return restored;
    }

    /**
     * Puts a player hide into the engine, recording whether this hold is the one that placed it.
     *
     * <p>A subject that some other mod already hides is left exactly as it is: the entry is already
     * there, and claiming ownership of it would mean lifting someone else's hide on release.
     *
     * <p>Non-player subjects are simply not known to {@link HiddenPlayersManager}; recording them here
     * is enough, because the entity visibility system reads these sets directly.
     */
    private void acquireEngineHide(UUID viewer, UUID target, Hold hold) {
        sessions.runOnWorld(viewer, (reference, store) -> {
            PlayerRef viewerRef = sessions.playerRef(viewer);
            if (viewerRef == null) {
                return;
            }
            HiddenPlayersManager manager = viewerRef.getHiddenPlayersManager();
            if (manager == null || !isOnlinePlayer(target)) {
                // Non-player subjects are not known to the manager, and a subject who is offline right
                // now gets picked up by enforcePlayerHides on the tick after they appear.
                return;
            }
            if (manager.isPlayerHidden(target)) {
                // Already hidden by someone else — most likely MysticVanish. Share the entry; do not
                // take responsibility for removing it.
                hold.ownsEngineHide = false;
            } else {
                manager.hidePlayer(target);
                hold.ownsEngineHide = true;
            }
            warnIfMarkersDefeatHiding(viewer, store.getComponent(
                    reference, EntityModule.get().getPlayerSettingsComponentType()));
        });
    }

    /**
     * Lifts a player hide, but only when this hold placed it and nothing else still wants it hidden.
     *
     * <p>The MysticVanish check matters even for a hide we own: a player can be hidden by a quest
     * first and vanish afterwards, at which point the engine set holds one entry that two mods
     * depend on. Releasing ours would strip the vanish along with it.
     */
    private void releaseEngineHide(UUID viewer, UUID target, Hold hold) {
        if (!hold.ownsEngineHide) {
            return;
        }
        hold.ownsEngineHide = false;
        if (vanish != null && vanish.wantsHidden(viewer, target)) {
            return;
        }
        sessions.runOnWorld(viewer, (reference, store) -> {
            PlayerRef viewerRef = sessions.playerRef(viewer);
            if (viewerRef == null) {
                return;
            }
            HiddenPlayersManager manager = viewerRef.getHiddenPlayersManager();
            if (manager != null) {
                manager.showPlayer(target);
            }
        });
    }

    /**
     * True when this UUID belongs to a player who is online right now.
     *
     * <p>{@link HiddenPlayersManager} only ever holds players, so this is what separates the subjects
     * the engine can hide from the NPCs and props handled by
     * {@link org.hyzionstudios.mysticquests.hytale.EntityVisibilitySystem}.
     */
    private boolean isOnlinePlayer(UUID subject) {
        return sessions.playerRef(subject) != null;
    }

    /**
     * The engine's hide pass bails out entirely for a viewer with entity markers on, so a quest that
     * hides someone from them appears to do nothing. Say so once, rather than letting it read as a
     * MysticQuests bug.
     */
    private void warnIfMarkersDefeatHiding(UUID viewer, @Nullable PlayerSettings settings) {
        if (logger == null || settings == null || !settings.showEntityMarkers() || !warnedAboutMarkers.add(viewer)) {
            return;
        }
        logger.at(Level.INFO).log("Player " + viewer + " has entity markers enabled, so their client still"
                + " renders players MysticQuests hides from them. This is an engine limitation, not a"
                + " quest error.");
    }

    /**
     * One viewer/subject pair's hide.
     *
     * <p>{@code ownsEngineHide} is written on the world thread that owns the viewer and read from
     * both that thread and the tick systems, so it is volatile. It is false both for a subject the
     * engine set never held — a non-player entity — and for a player another mod had already hidden,
     * because in neither case is the entry ours to remove.
     */
    private static final class Hold {
        private final EnumSet<Source> sources;

        /** True when this hold is the one that put the UUID into the engine set. */
        private volatile boolean ownsEngineHide;

        private Hold(Source source) {
            this.sources = EnumSet.of(source);
        }
    }
}
