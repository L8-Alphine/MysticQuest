package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink.Cue;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink.Placement;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink.Subtitle;
import org.hyzionstudios.mysticquests.narrative.session.QuestSession;
import org.hyzionstudios.mysticquests.narrative.session.QuestSessionService;
import org.hyzionstudios.mysticquests.narrative.session.SessionOwner;
import org.hyzionstudios.mysticquests.narrative.state.OwnerState;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.ScopeOwner;
import org.hyzionstudios.mysticquests.narrative.state.StateHost;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Consumer;
import java.util.function.Function;
import java.util.function.Supplier;

/**
 * §13 QuestMediaService: decides who hears which story audio, in which language, and in what order.
 *
 * <p>Every sound is addressed to single listeners resolved from an {@link Audience}, so two players
 * in the same room who are in different sessions hear different lines. Channel state (what is
 * playing, what is queued) is presentation only and lives in memory; a player who reconnects
 * simply hears the next line. Music is story state: the selected track is stored with the session,
 * player, party or server that chose it and resolved per listener like trigger overrides, so it is
 * rebuilt from state after a reconnect or restart.
 */
public final class QuestMediaService {
    /** A player's chosen voice language (§16), independent of their client language; unset follows the client. */
    public static final NamespacedId VOICE_LOCALE_VARIABLE = NamespacedId.of("mysticquests", "media.voice_locale");
    /** False when a player turned subtitles off (§16); unset means on. */
    public static final NamespacedId SUBTITLES_VARIABLE = NamespacedId.of("mysticquests", "media.subtitles");

    /** A player's audio preferences; {@code voiceLocale} is null when it follows their client. */
    public record Preferences(@Nullable String voiceLocale, boolean subtitles) {
    }

    /** Stores the music a scope selected; a reserved variable, so it persists with its owner. */
    public static final NamespacedId MUSIC_VARIABLE = NamespacedId.of("mysticquests", "media.music");
    /** Stored instead of a track to silence story music at one level, letting the world's own music play. */
    public static final String NO_MUSIC = "none";
    private static final int MAX_QUEUE = 16;

    /** Who hears a sound (§18.1). AREA is covered by {@link Spatial#POSITION}, which fades with distance. */
    public enum Audience {
        PLAYER, PARTY, STORY_SESSION, WORLD, GLOBAL;

        @Nullable
        public static Audience parse(@Nullable String raw) {
            if (raw == null || raw.isBlank()) {
                return null;
            }
            String wanted = raw.trim().toUpperCase(Locale.ROOT);
            if (wanted.equals("SESSION")) {
                return STORY_SESSION;
            }
            try {
                return valueOf(wanted);
            } catch (IllegalArgumentException unknown) {
                return null;
            }
        }
    }

    /** What one play request did across its listeners. */
    public record PlayReport(int started, int queued, int ignored, int failed) {
        public int reached() {
            return started + queued;
        }
    }

    /** Which level chose a listener's music, for the debugger. Asset null means the world's own music. */
    public record MusicDecision(@Nullable MediaAsset asset, @Nullable TriggerScope decidedBy, @Nullable String owner) {
        @Nullable
        public String container() {
            return asset == null ? null : asset.music();
        }
    }

    private record Pending(MediaAsset asset, Placement placement, boolean subtitles) {
    }

    private static final class Channel {
        @Nullable
        MediaAsset current;
        Instant endsAt = Instant.MIN;
        final ArrayDeque<Pending> queue = new ArrayDeque<>();

        boolean busy(Instant now) {
            return current != null && now.isBefore(endsAt);
        }
    }

    private final Supplier<NarrativeContent> content;
    private final StateHost host;
    private final String serverId;
    private final Function<UUID, Optional<String>> partyOf;
    private final Function<UUID, List<String>> activeSessionsOf;
    private final QuestSessionService sessions;
    private final Clock clock;
    private final Consumer<String> problems;
    private final Map<UUID, Map<String, Channel>> channels = new HashMap<>();
    private final Set<String> reported = ConcurrentHashMap.newKeySet();
    private volatile MediaSink sink = MediaSink.NONE;
    private volatile MediaCatalog catalog = MediaCatalog.UNKNOWN;
    private volatile String fallbackLocale = "en-US";

    public QuestMediaService(Supplier<NarrativeContent> content, StateHost host, String serverId,
                             Function<UUID, Optional<String>> partyOf, Function<UUID, List<String>> activeSessionsOf,
                             QuestSessionService sessions, Clock clock, Consumer<String> problems) {
        this.content = content;
        this.host = host;
        this.serverId = serverId;
        this.partyOf = partyOf;
        this.activeSessionsOf = activeSessionsOf;
        this.sessions = sessions;
        this.clock = clock;
        this.problems = problems;
    }

    /** Connects the engine. Bind before content loads so asset checks run against it. */
    public void bind(MediaSink sink, MediaCatalog catalog, String fallbackLocale) {
        this.sink = Objects.requireNonNull(sink, "sink");
        this.catalog = Objects.requireNonNull(catalog, "catalog");
        this.fallbackLocale = fallbackLocale == null || fallbackLocale.isBlank() ? "en-US" : fallbackLocale.trim();
    }

    public MediaCatalog catalog() {
        return catalog;
    }

    public String fallbackLocale() {
        return fallbackLocale;
    }

    @Nullable
    public MediaAsset asset(NamespacedId id) {
        return content.get().media().get(id);
    }

    // --- Audiences ---

    /** The online players an audience reaches, in a stable order. */
    public Set<UUID> listeners(Audience audience, ScopeContext context) {
        MediaSink current = sink;
        Set<UUID> online = new LinkedHashSet<>(current.online());
        Set<UUID> listeners = new LinkedHashSet<>();
        UUID actor = context.actor();
        switch (audience) {
            case PLAYER -> addIfOnline(actor, online, listeners);
            case PARTY -> {
                String party = context.partyId() != null ? context.partyId() : actor == null ? null : partyOf.apply(actor).orElse(null);
                if (party == null) {
                    addIfOnline(actor, online, listeners);
                } else {
                    partyMembers(party, online, listeners);
                }
            }
            case STORY_SESSION -> {
                Optional<QuestSession> session = context.sessionId() == null ? Optional.empty() : sessions.get(context.sessionId());
                if (session.isEmpty()) {
                    addIfOnline(actor, online, listeners);
                } else if (session.get().owner().kind() == SessionOwner.Kind.PLAYER) {
                    addIfOnline(session.get().owner().playerId(), online, listeners);
                } else {
                    partyMembers(session.get().owner().id(), online, listeners);
                }
            }
            case WORLD -> {
                String world = context.world() != null ? context.world() : actor == null ? null : current.worldOf(actor);
                for (UUID player : online) {
                    if (world != null && world.equals(current.worldOf(player))) {
                        listeners.add(player);
                    }
                }
            }
            case GLOBAL -> listeners.addAll(online);
        }
        return listeners;
    }

    private void partyMembers(String party, Set<UUID> online, Set<UUID> into) {
        for (UUID player : online) {
            if (partyOf.apply(player).map(party::equals).orElse(false)) {
                into.add(player);
            }
        }
    }

    private static void addIfOnline(@Nullable UUID player, Set<UUID> online, Set<UUID> into) {
        if (player != null && online.contains(player)) {
            into.add(player);
        }
    }

    // --- Playback ---

    public PlayReport play(ScopeContext context, NamespacedId assetId, Audience audience, Placement placement, boolean subtitles) {
        MediaAsset asset = asset(assetId);
        if (asset == null) {
            report("missing:" + assetId, "media " + assetId + " is not in the loaded content");
            return new PlayReport(0, 0, 0, 0);
        }
        return play(listeners(audience, context), asset, placement, subtitles);
    }

    /**
     * Plays an asset to each listener, applying its channel's interruption policy separately for
     * each one: Alice can be mid-line while Bob, in another session, hears the same line at once.
     */
    public synchronized PlayReport play(Iterable<UUID> listeners, MediaAsset asset, Placement placement, boolean subtitles) {
        if (asset.kind() == MediaKind.MUSIC) {
            report("music-play:" + asset.id(), "media " + asset.id() + " is music; switch it with mysticquests:music.set");
            return new PlayReport(0, 0, 0, 0);
        }
        Instant now = clock.instant();
        int started = 0;
        int queued = 0;
        int ignored = 0;
        int failed = 0;
        for (UUID listener : listeners) {
            Pending pending = new Pending(asset, placement, subtitles);
            String channelName = asset.channel();
            if (channelName == null || asset.interruption() == Interruption.MIX) {
                if (start(listener, pending, null, now)) {
                    started++;
                } else {
                    failed++;
                }
                continue;
            }
            Channel channel = channels.computeIfAbsent(listener, ignoredKey -> new HashMap<>())
                    .computeIfAbsent(channelName, ignoredKey -> new Channel());
            Interruption policy = asset.interruption();
            if (policy == Interruption.REPLACE_SAME_SPEAKER) {
                boolean sameSpeaker = channel.current != null && asset.speaker() != null
                        && asset.speaker().equals(channel.current.speaker());
                policy = sameSpeaker ? Interruption.INTERRUPT : Interruption.QUEUE;
            }
            if (!channel.busy(now) && channel.queue.isEmpty()) {
                policy = Interruption.INTERRUPT;
            }
            switch (policy) {
                case INTERRUPT -> {
                    channel.queue.clear();
                    if (start(listener, pending, channel, now)) {
                        started++;
                    } else {
                        failed++;
                    }
                }
                case IGNORE_NEW -> ignored++;
                default -> {
                    if (channel.queue.size() >= MAX_QUEUE) {
                        report("queue-full:" + listener + ":" + channelName, "media channel '" + channelName + "' for "
                                + listener + " already holds " + MAX_QUEUE + " lines; dropped " + asset.id());
                        ignored++;
                    } else {
                        channel.queue.addLast(pending);
                        queued++;
                    }
                }
            }
        }
        return new PlayReport(started, queued, ignored, failed);
    }

    /**
     * Starts one cue. A missing recording or asset still shows the subtitle, so the story stays
     * readable when audio is absent (§26.2 "Voice line missing at runtime").
     */
    private boolean start(UUID listener, Pending pending, @Nullable Channel channel, Instant now) {
        MediaAsset asset = pending.asset();
        MediaSink current = sink;
        Preferences preferences = preferences(listener);
        String wanted = preferences.voiceLocale() != null ? preferences.voiceLocale() : current.locale(listener);
        MediaAsset.Recording recording = asset.recordingFor(wanted, fallbackLocale);
        boolean played = false;
        if (recording == null) {
            report("no-recording:" + asset.id() + ":" + MediaAsset.normalise(wanted),
                    "media " + asset.id() + " has no recording for " + wanted + " and no fallback");
        } else {
            if (recording.fellBack()) {
                report("fallback:" + asset.id() + ":" + MediaAsset.normalise(wanted), "media " + asset.id() + " has no "
                        + wanted + " recording; playing " + (recording.locale().isEmpty() ? "the default" : recording.locale()));
            }
            played = current.play(listener, new Cue(asset, recording.sound(), pending.placement()));
            if (!played) {
                report("unplayable:" + recording.sound(), "SoundEvent '" + recording.sound() + "' of media " + asset.id()
                        + " could not be played (not loaded on this server?); showing the subtitle only");
            }
        }
        boolean subtitles = pending.subtitles() && asset.hasSubtitle() && preferences.subtitles();
        if (subtitles) {
            Speaker speaker = asset.speaker() == null ? null : content.get().speakers().get(asset.speaker());
            current.subtitle(listener, new Subtitle(speaker, asset.subtitle(), asset.subtitleKey(), asset.durationMillis()));
        }
        if (channel != null) {
            channel.current = asset;
            channel.endsAt = now.plusMillis(asset.durationMillis());
        }
        return played || subtitles;
    }

    // --- Player preferences (§16) ---

    /** What a player chose: a voice language that can differ from their client's, and subtitles on or off. */
    public Preferences preferences(UUID player) {
        OwnerState state = host.state(ScopeOwner.player(player));
        if (state == null) {
            return new Preferences(null, true);
        }
        String locale = state.variable(VOICE_LOCALE_VARIABLE) instanceof QuestValue.StringValue(String value) ? value : null;
        boolean subtitles = !(state.variable(SUBTITLES_VARIABLE) instanceof QuestValue.BoolValue(boolean on)) || on;
        return new Preferences(locale, subtitles);
    }

    /**
     * Sets the language a player hears voice lines in; null follows their client again. Subtitles
     * written with a {@code subtitleKey} stay in the client's language, so a player can hear one
     * language and read another.
     */
    public boolean setVoiceLocale(UUID player, @Nullable String locale) {
        OwnerState state = host.state(ScopeOwner.player(player));
        if (state == null) {
            return false;
        }
        String normalised = locale == null || locale.isBlank() ? null : locale.trim();
        return normalised == null ? state.removeVariable(VOICE_LOCALE_VARIABLE)
                : state.putVariable(VOICE_LOCALE_VARIABLE, new QuestValue.StringValue(normalised));
    }

    public boolean setSubtitles(UUID player, boolean on) {
        OwnerState state = host.state(ScopeOwner.player(player));
        if (state == null) {
            return false;
        }
        return on ? state.removeVariable(SUBTITLES_VARIABLE) : state.putVariable(SUBTITLES_VARIABLE, new QuestValue.BoolValue(false));
    }

    /** Starts queued lines whose turn has come. Cheap when nothing is queued. */
    public synchronized void tick() {
        if (channels.isEmpty()) {
            return;
        }
        Instant now = clock.instant();
        Iterator<Map.Entry<UUID, Map<String, Channel>>> listeners = channels.entrySet().iterator();
        while (listeners.hasNext()) {
            Map.Entry<UUID, Map<String, Channel>> entry = listeners.next();
            Iterator<Channel> byName = entry.getValue().values().iterator();
            while (byName.hasNext()) {
                Channel channel = byName.next();
                if (channel.busy(now)) {
                    continue;
                }
                Pending next = channel.queue.pollFirst();
                if (next != null) {
                    start(entry.getKey(), next, channel, now);
                } else {
                    byName.remove();
                }
            }
            if (entry.getValue().isEmpty()) {
                listeners.remove();
            }
        }
    }

    /**
     * Ends what a channel is doing for these listeners and drops its queue. Audio already sent keeps
     * playing on the client: the engine has no stop packet.
     *
     * @param channel null for every channel
     * @return how many listeners had something to stop
     */
    public synchronized int stop(Iterable<UUID> listeners, @Nullable String channel) {
        int stopped = 0;
        for (UUID listener : listeners) {
            Map<String, Channel> own = channels.get(listener);
            if (own == null) {
                continue;
            }
            if (channel == null) {
                stopped += own.isEmpty() ? 0 : 1;
                channels.remove(listener);
            } else if (own.remove(channel) != null) {
                stopped++;
            }
        }
        return stopped;
    }

    public synchronized void onQuit(UUID player) {
        channels.remove(player);
    }

    /** What each of a listener's channels is playing and holding, for the debugger. */
    public synchronized List<String> describe(UUID player) {
        List<String> lines = new ArrayList<>();
        Instant now = clock.instant();
        Map<String, Channel> own = channels.getOrDefault(player, Map.of());
        own.forEach((name, channel) -> lines.add(name + ": "
                + (channel.busy(now) ? channel.current.id() + " (until " + channel.endsAt + ")" : "idle")
                + (channel.queue.isEmpty() ? "" : ", " + channel.queue.size() + " queued")));
        MusicDecision music = music(player);
        lines.add("music: " + (music.asset() == null ? "world" : music.asset().id())
                + (music.decidedBy() == null ? "" : " (" + music.decidedBy().name().toLowerCase(Locale.ROOT) + " " + music.owner() + ")"));
        return lines;
    }

    // --- Music ---

    /**
     * Selects story music at one level.
     *
     * @param music a music asset id, {@link #NO_MUSIC} to let the world's own music play at this
     *         level, or null to clear and fall back to the next level
     */
    public StateResult setMusic(TriggerScope scope, @Nullable String music, ScopeContext context) {
        ScopeOwner owner = switch (scope) {
            case GLOBAL -> serverOwner();
            case PLAYER -> context.actor() == null ? null : ScopeOwner.player(context.actor());
            case PARTY -> context.partyId() == null ? null : new ScopeOwner(VariableScope.PARTY, context.partyId());
            case STORY_SESSION -> context.sessionId() == null ? null : ScopeOwner.session(context.sessionId());
        };
        if (owner == null) {
            return StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED, "music: " + scope + " scope has no owner here");
        }
        OwnerState state = host.state(owner);
        if (state == null) {
            return StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED, "music: " + owner + " is not open");
        }
        boolean changed = music == null
                ? state.removeVariable(MUSIC_VARIABLE)
                : state.putVariable(MUSIC_VARIABLE, new QuestValue.StringValue(music));
        return changed ? StateResult.changed(null) : StateResult.unchanged(null);
    }

    /**
     * The music a listener should hear: their sessions first, then themselves, their party and the
     * server, as for trigger overrides. A track removed from content in a reload counts as unset.
     */
    public MusicDecision music(UUID player) {
        for (String sessionId : activeSessionsOf.apply(player)) {
            MusicDecision decision = musicAt(ScopeOwner.session(sessionId), TriggerScope.STORY_SESSION, sessionId);
            if (decision != null) {
                return decision;
            }
        }
        MusicDecision decision = musicAt(ScopeOwner.player(player), TriggerScope.PLAYER, player.toString());
        if (decision != null) {
            return decision;
        }
        Optional<String> party = partyOf.apply(player);
        if (party.isPresent()) {
            decision = musicAt(new ScopeOwner(VariableScope.PARTY, party.get()), TriggerScope.PARTY, party.get());
            if (decision != null) {
                return decision;
            }
        }
        decision = musicAt(serverOwner(), TriggerScope.GLOBAL, serverId);
        return decision != null ? decision : new MusicDecision(null, null, null);
    }

    @Nullable
    private MusicDecision musicAt(ScopeOwner owner, TriggerScope level, String ownerId) {
        OwnerState state = host.state(owner);
        if (state == null || !(state.variable(MUSIC_VARIABLE) instanceof QuestValue.StringValue(String value))) {
            return null;
        }
        if (value.equals(NO_MUSIC)) {
            return new MusicDecision(null, level, ownerId);
        }
        MediaAsset asset = NamespacedId.tryParse(value).map(this::asset).orElse(null);
        if (asset == null || asset.kind() != MediaKind.MUSIC) {
            report("stale-music:" + value, "stored music '" + value + "' is no longer a music asset; ignoring it");
            return null;
        }
        return new MusicDecision(asset, level, ownerId);
    }

    private ScopeOwner serverOwner() {
        return new ScopeOwner(VariableScope.SERVER, serverId);
    }

    /** Reports each distinct problem once, so a missing recording does not flood the log every line. */
    private void report(String key, String message) {
        if (reported.add(key)) {
            problems.accept(message);
        }
    }
}
