package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.narrative.media.MediaCatalog;
import org.hyzionstudios.mysticquests.narrative.media.MediaKind;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink;
import org.hyzionstudios.mysticquests.narrative.media.Speaker;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;

import com.hypixel.hytale.assetstore.map.AssetMapWithIndexes;
import com.hypixel.hytale.builtin.audio.AudioPlugin;
import com.hypixel.hytale.builtin.audio.components.ForcedMusicTracker;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.protocol.Position;
import com.hypixel.hytale.protocol.SoundCategory;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEvent3D;
import com.hypixel.hytale.protocol.packets.world.PlaySoundEventEntity;
import com.hypixel.hytale.server.core.Message;
import com.hypixel.hytale.server.core.asset.type.musiccontainer.config.MusicContainer;
import com.hypixel.hytale.server.core.asset.type.soundevent.config.SoundEvent;
import com.hypixel.hytale.server.core.modules.entity.tracker.NetworkId;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.SoundUtil;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import com.hypixel.hytale.server.core.util.EventTitleUtil;

import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;

/**
 * Plays narrative media through the engine, one listener at a time (§13, §18).
 *
 * <ul>
 *   <li>Sounds are written to the listener's own connection ({@code PlaySoundEvent2D}, {@code 3D} or
 *       {@code Entity} packets), on the listener's world thread, so nobody else hears them.</li>
 *   <li>Music sets the listener's {@link ForcedMusicTracker}, the per-player override the engine's
 *       {@code /audio music force} command uses. MysticQuests only writes it when its own choice
 *       changes, so an encounter that forces boss music in between is not fought every tick.</li>
 *   <li>Subtitles go to chat or the event title, as configured; Hytale has no subtitle HUD.</li>
 * </ul>
 */
public final class HytaleMedia implements MediaSink, MediaCatalog, AutoCloseable {
    private static final long TICK_MILLIS = 250;
    private static final int MUSIC_EVERY_TICKS = 4;

    /** Where subtitles appear. */
    public enum SubtitleMode {
        CHAT, TITLE, OFF;

        static SubtitleMode parse(@Nullable String raw, Consumer<String> problems) {
            try {
                return raw == null || raw.isBlank() ? CHAT : valueOf(raw.trim().toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException unknown) {
                problems.accept("Unknown narrative.subtitles '" + raw + "'; using chat.");
                return CHAT;
            }
        }
    }

    private final NarrativeRuntime narrative;
    private final PlayerSessionService players;
    private final SubtitleMode subtitles;
    private final Consumer<String> problems;
    private final Map<UUID, Integer> appliedMusic = new ConcurrentHashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private final ScheduledExecutorService ticker;
    private volatile boolean musicSupported = true;
    private int ticks;

    public HytaleMedia(NarrativeRuntime narrative, PlayerSessionService players, @Nullable String subtitles,
                       Consumer<String> problems) {
        this.narrative = narrative;
        this.players = players;
        this.problems = problems;
        this.subtitles = SubtitleMode.parse(subtitles, problems);
        this.ticker = Executors.newSingleThreadScheduledExecutor(runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-Media");
            thread.setDaemon(true);
            return thread;
        });
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    private void tick() {
        try {
            narrative.media().tick();
            // Cutscenes advance on their driving player's world thread: their steps may touch that player.
            for (UUID driver : narrative.cutscenes().drivers()) {
                players.runOnWorld(driver, (entity, store) -> narrative.cutscenes().advance(driver));
            }
            if (++ticks % MUSIC_EVERY_TICKS == 0) {
                reconcileMusic();
            }
        } catch (RuntimeException failure) {
            problems.accept("media tick failed: " + failure);
        }
    }

    // --- MediaSink ---

    @Override
    public Collection<UUID> online() {
        List<UUID> online = new ArrayList<>();
        players.onlinePlayerIds().forEach(online::add);
        return online;
    }

    @Override
    @Nullable
    public String worldOf(UUID player) {
        PlayerRef ref = players.playerRef(player);
        return ref == null || ref.getWorldUuid() == null ? null : ref.getWorldUuid().toString();
    }

    @Override
    @Nullable
    public String locale(UUID player) {
        PlayerRef ref = players.playerRef(player);
        return ref == null ? null : ref.getLanguage();
    }

    @Override
    public boolean play(UUID listener, Cue cue) {
        if (players.playerRef(listener) == null) {
            return false;
        }
        int index = SoundEvent.getAssetMap().getIndex(cue.sound());
        if (index == AssetMapWithIndexes.NOT_FOUND || index == SoundEvent.EMPTY_ID) {
            return false;
        }
        SoundCategory category = category(cue.asset().kind());
        float volume = cue.asset().volume();
        float pitch = cue.asset().pitch();
        Placement placement = cue.placement();
        players.runOnWorld(listener, (playerEntity, store) -> {
            PlayerRef ref = store.getComponent(playerEntity, PlayerRef.getComponentType());
            if (ref == null) {
                return;
            }
            switch (placement.spatial()) {
                case TWO_D -> SoundUtil.playSoundEvent2dToPlayer(ref, index, category, volume, pitch);
                case POSITION -> ref.getPacketHandler().write(new PlaySoundEvent3D(index, category,
                        new Position(placement.x(), placement.y(), placement.z()), volume, pitch));
                case ENTITY -> {
                    Integer networkId = networkId(placement, store);
                    if (networkId == null) {
                        report("speaker:" + cue.asset().id(), "the entity for media " + cue.asset().id()
                                + " is not loaded near the listener; playing it without position");
                        SoundUtil.playSoundEvent2dToPlayer(ref, index, category, volume, pitch);
                    } else {
                        ref.getPacketHandler().write(new PlaySoundEventEntity(index, networkId, volume, pitch));
                    }
                }
            }
        });
        return true;
    }

    @Nullable
    private Integer networkId(Placement placement, Store<EntityStore> store) {
        if (placement.entity() == null) {
            return null;
        }
        Optional<UUID> body = narrative.storyEntities().liveBody(placement.entity());
        Ref<EntityStore> target = body.map(uuid -> store.getExternalData().getRefFromUUID(uuid)).orElse(null);
        if (target == null || !target.isValid()) {
            return null;
        }
        NetworkId networkId = store.getComponent(target, NetworkId.getComponentType());
        return networkId == null ? null : networkId.getId();
    }

    @Override
    public void subtitle(UUID listener, Subtitle subtitle) {
        PlayerRef ref = players.playerRef(listener);
        if (ref == null || subtitles == SubtitleMode.OFF) {
            return;
        }
        Message line = subtitle.key() != null ? Message.translation(subtitle.key()) : Message.raw(subtitle.text());
        Message name = speakerName(subtitle.speaker());
        if (subtitles == SubtitleMode.TITLE) {
            float seconds = Math.max(2f, subtitle.durationMillis() / 1000f);
            EventTitleUtil.showEventTitleToPlayer(ref, line, name == null ? Message.raw("") : name, false, null, seconds, 0.2f, 0.4f);
        } else {
            ref.sendMessage(name == null ? line : Message.join(name.color("#F5C842"), Message.raw(": "), line));
        }
    }

    @Nullable
    private static Message speakerName(@Nullable Speaker speaker) {
        if (speaker == null) {
            return null;
        }
        if (speaker.nameKey() != null) {
            return Message.translation(speaker.nameKey());
        }
        return speaker.name() == null ? null : Message.raw(speaker.name());
    }

    private static SoundCategory category(MediaKind kind) {
        return switch (kind) {
            case VOICE -> SoundCategory.Voice;
            case AMBIENT -> SoundCategory.Ambient;
            case MUSIC, STINGER -> SoundCategory.Music;
            case UI -> SoundCategory.UI;
            case SFX, CINEMATIC -> SoundCategory.SFX;
        };
    }

    // --- Music ---

    /**
     * Brings each online player's forced music in line with their story state. Runs every second,
     * so a session ending, a party change or a reconnect all converge without event wiring.
     */
    private void reconcileMusic() {
        if (!musicSupported) {
            return;
        }
        try {
            if (AudioPlugin.get() == null) {
                musicSupported = false;
                problems.accept("The Audio plugin is not loaded; story music is disabled.");
                return;
            }
        } catch (LinkageError missing) {
            musicSupported = false;
            problems.accept("The Audio plugin is not available on this server; story music is disabled.");
            return;
        }
        for (UUID player : players.onlinePlayerIds()) {
            String container = narrative.media().music(player).container();
            int index = 0;
            if (container != null) {
                index = MusicContainer.getAssetMap().getIndex(container);
                if (index == AssetMapWithIndexes.NOT_FOUND) {
                    report("music:" + container, "MusicContainer '" + container + "' is not loaded; playing the world's own music");
                    index = 0;
                }
            }
            Integer applied = appliedMusic.get(player);
            if ((applied == null && index == 0) || (applied != null && applied == index)) {
                continue;
            }
            if (index == 0) {
                appliedMusic.remove(player);
            } else {
                appliedMusic.put(player, index);
            }
            int target = index;
            players.runOnWorld(player, (playerEntity, store) -> {
                ForcedMusicTracker tracker = store.getComponent(playerEntity, ForcedMusicTracker.getComponentType());
                if (tracker != null) {
                    tracker.setCurrentContainerIndex(target);
                }
            });
        }
        // The engine clears forced music when the player entity is removed, so forget quitters.
        appliedMusic.keySet().removeIf(player -> players.playerRef(player) == null);
    }

    // --- MediaCatalog ---

    @Override
    @Nullable
    public Boolean soundExists(String soundEvent) {
        try {
            return SoundEvent.getAssetMap().getIndex(soundEvent) != AssetMapWithIndexes.NOT_FOUND;
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    @Override
    @Nullable
    public Boolean musicExists(String musicContainer) {
        try {
            return MusicContainer.getAssetMap().getIndex(musicContainer) != AssetMapWithIndexes.NOT_FOUND;
        } catch (RuntimeException notReady) {
            return null;
        }
    }

    private void report(String key, String message) {
        if (reported.add(key)) {
            problems.accept(message);
        }
    }

    @Override
    public void close() {
        ticker.shutdownNow();
    }
}
