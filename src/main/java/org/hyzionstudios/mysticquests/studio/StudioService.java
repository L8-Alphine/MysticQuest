package org.hyzionstudios.mysticquests.studio;

import java.util.concurrent.ConcurrentHashMap;
import java.time.Instant;
import java.time.Duration;
import java.time.Clock;
import java.util.Map;
import org.hyzionstudios.mysticquests.service.PlayerStateAdmin;
import org.hyzionstudios.mysticquests.studio.StudioAuth.Session;
import org.hyzionstudios.mysticquests.studio.StudioReleases.Release;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace.Change;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace.FileContent;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace.FileInfo;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;
import java.io.IOException;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

/**
 * Everything the Studio can do, each call authorized on the server against the signed-in player's
 * current permissions (§23: "never trust only client UI hiding"). The web layer only maps requests
 * onto these methods.
 *
 * <p>Every request needs {@link StudioCapability#LOGIN}, so removing it ends a user's access at
 * once. Reading needs {@link StudioCapability#VIEW}; an edit needs the capability of every content
 * section it changes ({@link StudioSections}); publishing needs {@link StudioCapability#PUBLISH}.
 */
public final class StudioService {
    /** Who is signed in and what they may do, for the Studio to show only what applies. */
    public record Me(UUID player, String name, Set<StudioCapability> capabilities, String environment, String csrf) {
    }

    private final StudioAuth auth;
    private final StudioWorkspace workspace;
    private final StudioReleases releases;
    private final StudioAudit audit;
    private final StudioCapability.Permissions permissions;
    private final StudioValidation.Validator validator;
    private final String environment;
    private final StudioLive live;
    private volatile StudioOidc identity;
    private volatile StudioAudio audio;
    private volatile java.nio.file.Path soundPack;
    private volatile String serverVersion = "*";
    private final Clock clock;
    /** Sessions that read live state recently, by token, for the observer limit (§28). */
    private final Map<String, Instant> liveObservers = new ConcurrentHashMap<>();

    /** @param environment this server's publish target, shown in the Studio: {@code development}, {@code staging} or {@code production} */
    public StudioService(StudioAuth auth, StudioWorkspace workspace, StudioReleases releases, StudioAudit audit,
                         StudioCapability.Permissions permissions, StudioValidation.Validator validator, String environment) {
        this(auth, workspace, releases, audit, permissions, validator, environment, StudioLive.NONE, Clock.systemUTC());
    }

    /** @param live the running server's state, for the Live Sessions page */
    public StudioService(StudioAuth auth, StudioWorkspace workspace, StudioReleases releases, StudioAudit audit,
                         StudioCapability.Permissions permissions, StudioValidation.Validator validator, String environment,
                         StudioLive live, Clock clock) {
        this.live = live;
        this.clock = clock;
        this.validator = validator;
        this.auth = auth;
        this.workspace = workspace;
        this.releases = releases;
        this.audit = audit;
        this.permissions = permissions;
        this.environment = environment;
    }

    public StudioAuth auth() {
        return auth;
    }

    public String environment() {
        return environment;
    }

    // --- Sign-in ---

    /** Redeems an in-game code. Refused, and audited, when the player lacks the login capability. */
    public Session signIn(String code, String address) throws StudioException {
        Session session = auth.redeem(code, address);
        if (!StudioCapability.LOGIN.grantedTo(session.player(), permissions)) {
            auth.logout(session.token());
            audit.record(session.actor(), "sign-in refused", "", "no " + StudioCapability.LOGIN.permission());
            throw new StudioException(StudioException.Status.FORBIDDEN, "You need " + StudioCapability.LOGIN.permission() + " to use the Studio.");
        }
        audit.record(session.actor(), "signed in", "", address);
        return session;
    }

    /** Turns on signing in with MysticIdentity accounts; null turns it off. */
    public void identitySignIn(@Nullable StudioOidc identity) {
        this.identity = identity;
    }

    /** What the sign-in page offers. */
    public record SignInOptions(boolean codes, boolean mysticIdentity) {
    }

    public SignInOptions signInOptions() {
        return new SignInOptions(true, identity != null);
    }

    /** Where to send the browser to sign in with MysticIdentity, and the state to bind to it. */
    public StudioOidc.Start beginIdentitySignIn() throws StudioException {
        StudioOidc oidc = identity;
        if (oidc == null) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "Signing in with MysticIdentity is not set up on this server.");
        }
        return oidc.begin();
    }

    /** Finishes a MysticIdentity sign-in; refused, and audited, without the login permission. */
    public Session completeIdentitySignIn(String state, String code, String address) throws StudioException {
        StudioOidc oidc = identity;
        if (oidc == null) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "Signing in with MysticIdentity is not set up on this server.");
        }
        StudioOidc.Identity who = oidc.complete(state, code);
        if (!StudioCapability.LOGIN.grantedTo(who.player(), permissions)) {
            audit.record(who.name() + " (" + who.player() + ")", "sign-in refused", "", "MysticIdentity; no " + StudioCapability.LOGIN.permission());
            throw new StudioException(StudioException.Status.FORBIDDEN, "You need " + StudioCapability.LOGIN.permission() + " to use the Studio.");
        }
        Session session = auth.open(who.player(), who.name());
        audit.record(session.actor(), "signed in", "", "MysticIdentity, " + address);
        return session;
    }

    public void signOut(Session session) {
        auth.logout(session.token());
        audit.record(session.actor(), "signed out", "", "");
    }

    public Me me(Session session) throws StudioException {
        require(session, StudioCapability.LOGIN);
        return new Me(session.player(), session.name(), StudioCapability.of(session.player(), permissions), environment, session.csrf());
    }

    // --- Draft ---

    public List<FileInfo> files(Session session) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return workspace.files();
    }

    public FileContent read(Session session, String path) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return workspace.read(ContentRoot.validate(path));
    }

    /**
     * Saves a draft file.
     *
     * @param version the version the edit started from, or null to create the file
     * @return the new version
     */
    public String write(Session session, String path, String text, @Nullable String version) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        ContentRoot.validate(path);
        synchronized (workspace) {
            String before = workspace.textOrNull(path);
            workspace.parse(path, text);
            requireSections(session, path, before, text);
            String saved = workspace.write(path, text, version);
            audit.record(session.actor(), before == null ? "created" : "edited", path,
                    String.join(",", StudioSections.changed(path, before, text, workspace.parser(path))));
            return saved;
        }
    }

    /**
     * A draft file parsed for the form editors.
     *
     * @param document the parsed content, or null when the file does not parse ({@code error} says why)
     * @param hasComments whether saving through a form would drop YAML comments
     */
    public record Document(String path, String version, @Nullable JsonNode document, boolean hasComments, @Nullable String error) {
    }

    public Document document(Session session, String path) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return parsed(workspace.read(ContentRoot.validate(path)));
    }

    /** Every draft file, parsed; the Studio builds its quest, dialogue and puzzle views from these. */
    public List<Document> documents(Session session) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        List<Document> documents = new java.util.ArrayList<>();
        for (FileInfo file : workspace.files()) {
            documents.add(parsed(workspace.read(file.path())));
        }
        return documents;
    }

    private Document parsed(FileContent file) {
        boolean comments = StudioWorkspace.hasComments(file.path(), file.text());
        try {
            return new Document(file.path(), file.version(), workspace.parse(file.path(), file.text()), comments, null);
        } catch (StudioException unparseable) {
            return new Document(file.path(), file.version(), null, comments, unparseable.getMessage());
        }
    }

    /** Saves a document edited in a form, written back in the file's own format. */
    public String writeDocument(Session session, String path, JsonNode document, @Nullable String version) throws StudioException, IOException {
        ContentRoot.validate(path);
        return write(session, path, workspace.format(path, document), version);
    }

    public void delete(Session session, String path, String version) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        ContentRoot.validate(path);
        synchronized (workspace) {
            String before = workspace.textOrNull(path);
            if (before == null) {
                throw new StudioException(StudioException.Status.NOT_FOUND, "No file " + path + " in the draft.");
            }
            requireSections(session, path, before, null);
            workspace.delete(path, version);
            audit.record(session.actor(), "deleted", path, "");
        }
    }

    private void requireSections(Session session, String path, @Nullable String before, @Nullable String after) throws StudioException {
        for (StudioCapability needed : StudioSections.required(path, before, after, workspace.parser(path))) {
            if (!needed.grantedTo(session.player(), permissions)) {
                audit.record(session.actor(), "edit refused", path, "needs " + needed.permission());
                throw new StudioException(StudioException.Status.FORBIDDEN,
                        "This change touches content that needs " + needed.permission() + ".");
            }
        }
    }

    /** What publishing would change, and live files edited outside the Studio since the draft was made. */
    public record Status(List<Change> changes, List<Change> liveEdits) {
    }

    public Status status(Session session) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return new Status(workspace.changes(), workspace.liveDrift());
    }

    public StudioValidation validate(Session session) throws StudioException {
        require(session, StudioCapability.VIEW);
        return validator.validate(workspace.draft());
    }

    /** Discards the draft and starts again from the live content. */
    public void reset(Session session) throws StudioException, IOException {
        require(session, StudioCapability.EDIT);
        workspace.reset();
        audit.record(session.actor(), "reset the draft", "", "");
    }

    // --- Releases ---

    public Release publish(Session session, String message, boolean overwriteLiveEdits) throws StudioException, IOException {
        require(session, StudioCapability.PUBLISH);
        try {
            Release release = releases.publish(session.actor(), message, overwriteLiveEdits);
            audit.record(session.actor(), "published", "release " + release.number(), release.changes().size() + " file(s): " + release.message());
            return release;
        } catch (StudioException refused) {
            audit.record(session.actor(), "publish refused", "", refused.getMessage());
            throw refused;
        }
    }

    public List<Release> history(Session session) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return releases.history();
    }

    public List<Change> compare(Session session, int from, int to) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return releases.compare(from, to);
    }

    public byte[] exportRelease(Session session, int number) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        byte[] bundle = releases.export(number);
        audit.record(session.actor(), "exported", "release " + number, bundle.length + " bytes");
        return bundle;
    }

    /** Loads a release bundle from another server into the draft (§23 promotion); publishing it is a separate step. */
    public int importBundle(Session session, byte[] bundle) throws StudioException, IOException {
        require(session, StudioCapability.EDIT);
        int files = releases.importBundle(bundle);
        audit.record(session.actor(), "imported a release bundle into the draft", "", files + " file(s)");
        return files;
    }

    /** Loads a past release into the draft (§12 rollback); publishing it makes a new release. */
    public void restore(Session session, int number) throws StudioException, IOException {
        require(session, StudioCapability.EDIT);
        releases.restore(number);
        audit.record(session.actor(), "restored into the draft", "release " + number, "");
    }

    public List<StudioAudit.Entry> auditTrail(Session session, int limit) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return audit.recent(Math.max(1, Math.min(limit, 500)));
    }

    // --- Audio ---

    /** Turns on the audio pipeline, building into {@code soundPack} for engines in {@code serverVersion}. */
    public void audio(StudioAudio audio, java.nio.file.Path soundPack, String serverVersion) {
        this.audio = audio;
        this.soundPack = soundPack;
        this.serverVersion = serverVersion;
    }

    /** A clip with the SoundEvent id story media uses and its file in the generated pack. */
    public record ClipView(StudioAudio.Clip clip, String soundEvent, String file) {
        static ClipView of(StudioAudio.Clip clip) {
            return new ClipView(clip, clip.soundEvent(), clip.file());
        }
    }

    public record UploadView(ClipView clip, List<String> warnings) {
    }

    private StudioAudio audio() throws StudioException {
        StudioAudio current = audio;
        if (current == null) {
            throw new StudioException(StudioException.Status.NOT_FOUND, "The audio pipeline is not available on this server.");
        }
        return current;
    }

    public List<ClipView> audioClips(Session session) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return audio().clips().stream().map(ClipView::of).toList();
    }

    public byte[] audioBytes(Session session, String name) throws StudioException, IOException {
        require(session, StudioCapability.VIEW);
        return audio().bytes(name);
    }

    public UploadView uploadAudio(Session session, String name, String kind, byte[] bytes) throws StudioException, IOException {
        require(session, StudioCapability.AUDIO);
        StudioAudio.Upload upload = audio().upload(name, StudioAudio.Kind.parse(kind == null ? "" : kind), bytes, session.actor());
        audit.record(session.actor(), "uploaded audio", upload.clip().name(), upload.clip().kind() + ", " + upload.clip().seconds() + " s, "
                + upload.clip().channels() + " channel(s), " + upload.clip().bytes() + " bytes");
        return new UploadView(ClipView.of(upload.clip()), upload.warnings());
    }

    public void deleteAudio(Session session, String name) throws StudioException, IOException {
        require(session, StudioCapability.AUDIO);
        audio().delete(name);
        audit.record(session.actor(), "deleted audio", name, "");
    }

    /** Writes the generated sound pack; it changes what the server loads, so it also needs publish rights. */
    public StudioAudio.Build buildSoundPack(Session session) throws StudioException, IOException {
        require(session, StudioCapability.AUDIO);
        require(session, StudioCapability.PUBLISH);
        StudioAudio.Build build = audio().build(soundPack, serverVersion);
        audit.record(session.actor(), "built the sound pack", build.folder(), build.sounds() + " sound(s), " + build.removed() + " removed");
        return build;
    }

    // --- Live sessions ---

    /** The most Studio users that may watch live state at once; each one polls, nothing is streamed. */
    public static final int MAX_LIVE_OBSERVERS = 10;
    static final Duration OBSERVER_WINDOW = Duration.ofSeconds(30);

    public StudioLive.Overview liveOverview(Session session) throws StudioException {
        observe(session);
        return live.overview();
    }

    public Map<String, List<String>> livePlayer(Session session, UUID player) throws StudioException {
        observe(session);
        return live.player(player);
    }

    // --- Players (Redesign Bible §9.1) ---

    /** A player's quest and story state. Reading needs live access, like the Live Sessions page. */
    public PlayerStateAdmin.Snapshot playerState(Session session, UUID player) throws StudioException {
        observe(session);
        return live.playerState(player).orElseThrow(() ->
                new StudioException(StudioException.Status.NOT_FOUND, "No game server is attached to this Studio."));
    }

    /** Finds an online player by name, or any player by UUID. */
    public Map<String, String> findPlayer(Session session, String query) throws StudioException {
        require(session, StudioCapability.LIVE);
        String wanted = query == null ? "" : query.trim();
        if (wanted.isEmpty()) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "Type a player name or UUID.");
        }
        UUID found = live.findPlayer(wanted).orElseThrow(() -> new StudioException(StudioException.Status.NOT_FOUND,
                "No online player called " + wanted + ". Offline players are found by UUID."));
        return Map.of("player", found.toString());
    }

    /**
     * Changes a player's state (§9.1: explicit permission, a reason, and an audit entry). The change
     * itself is made by {@link PlayerStateAdmin}, which checks it the way the game would and writes
     * the gameplay audit; the Studio's own log records who asked for it from the browser.
     */
    public PlayerStateAdmin.Outcome changePlayer(Session session, UUID player, JsonNode body) throws StudioException {
        require(session, StudioCapability.PLAYERS);
        String action = body.path("action").asText("");
        String reason = body.path("reason").asText("");
        String actor = session.player().toString();
        java.util.function.Function<PlayerStateAdmin, PlayerStateAdmin.Outcome> change = playerChange(body, action, reason, actor, player);
        PlayerStateAdmin.Outcome outcome = live.changePlayer(player, change);
        audit.record(session.actor(), "player " + action, player.toString(),
                (outcome.ok() ? "" : "refused: ") + outcome.message() + (reason.isBlank() ? "" : " (" + reason + ")"));
        return outcome;
    }

    private static java.util.function.Function<PlayerStateAdmin, PlayerStateAdmin.Outcome> playerChange(
            JsonNode body, String action, String reason, String actor, UUID player) throws StudioException {
        String quest = body.path("quest").asText("");
        String id = body.path("id").asText("");
        String owner = body.path("owner").asText("");
        String value = body.path("value").asText("");
        return switch (action) {
            case "clear" -> {
                java.util.Set<PlayerStateAdmin.Part> parts = java.util.EnumSet.noneOf(PlayerStateAdmin.Part.class);
                for (JsonNode part : body.path("parts")) {
                    PlayerStateAdmin.Part parsed = PlayerStateAdmin.Part.parse(part.asText());
                    if (parsed == null) {
                        throw new StudioException(StudioException.Status.BAD_REQUEST, "Unknown part: " + part.asText());
                    }
                    parts.add(parsed);
                }
                yield admin -> admin.clear(actor, player, parts, reason);
            }
            case "quest.start" -> admin -> admin.startQuest(actor, player, quest, reason);
            case "quest.complete" -> admin -> admin.completeQuest(actor, player, quest, reason);
            case "quest.abandon" -> admin -> admin.abandonQuest(actor, player, quest, reason);
            case "quest.reset" -> admin -> admin.resetQuest(actor, player, quest, reason);
            case "quest.allow" -> admin -> admin.allowAgain(actor, player, quest, reason);
            case "quest.track" -> admin -> admin.trackQuest(actor, player, quest, reason);
            case "quest.objective" -> {
                if (!body.path("amount").canConvertToInt()) {
                    throw new StudioException(StudioException.Status.BAD_REQUEST, "'amount' must be a whole number.");
                }
                int amount = body.path("amount").asInt();
                String objective = body.path("objective").asText("");
                yield admin -> admin.setObjective(actor, player, quest, objective, amount, reason);
            }
            case "tag.add" -> admin -> admin.addTag(actor, player, id, reason);
            case "tag.remove" -> admin -> admin.removeTag(actor, player, id, reason);
            case "variable.set" -> admin -> admin.setVariable(actor, player, id, value, reason);
            case "variable.remove" -> admin -> admin.removeVariable(actor, player, id, reason);
            case "story.variable.set" -> admin -> admin.setStoryVariable(actor, player, id, value, reason);
            case "story.variable.remove" -> admin -> admin.removeStoryVariable(actor, player, owner, id, reason);
            case "story.tag.add" -> admin -> admin.addStoryTag(actor, player, id, reason);
            case "story.tag.remove" -> admin -> admin.removeStoryTag(actor, player, owner, id, reason);
            case "story.restart" -> admin -> admin.restartStory(actor, player, id, reason);
            case "story.rewind" -> admin -> admin.rewind(actor, player, id, reason);
            default -> throw new StudioException(StudioException.Status.BAD_REQUEST, "Unknown player action: " + action);
        };
    }

    /** People who read live state within the last {@link #OBSERVER_WINDOW}. */
    public int liveObservers() {
        Instant cutoff = clock.instant().minus(OBSERVER_WINDOW);
        liveObservers.values().removeIf(seen -> seen.isBefore(cutoff));
        return liveObservers.size();
    }

    private void observe(Session session) throws StudioException {
        require(session, StudioCapability.LIVE);
        synchronized (liveObservers) {
            if (!liveObservers.containsKey(session.token()) && liveObservers() >= MAX_LIVE_OBSERVERS) {
                throw new StudioException(StudioException.Status.TOO_MANY_REQUESTS,
                        MAX_LIVE_OBSERVERS + " people are already watching live sessions. Try again in a minute.");
            }
            liveObservers.put(session.token(), clock.instant());
        }
    }

    // --- Authorization ---

    /** @throws StudioException {@code FORBIDDEN} naming the missing permission */
    public void require(Session session, StudioCapability capability) throws StudioException {
        if (!StudioCapability.LOGIN.grantedTo(session.player(), permissions)) {
            auth.logout(session.token());
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "Your Studio access was removed.");
        }
        if (!capability.grantedTo(session.player(), permissions)) {
            throw new StudioException(StudioException.Status.FORBIDDEN, "You need " + capability.permission() + " for this.");
        }
    }

    /** The capabilities a player holds, by permission name, for diagnostics. */
    public String describe(UUID player) {
        return StudioCapability.of(player, permissions).stream().map(StudioCapability::permission).collect(Collectors.joining(", "));
    }
}
