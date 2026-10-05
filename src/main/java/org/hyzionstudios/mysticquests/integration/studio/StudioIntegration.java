package org.hyzionstudios.mysticquests.integration.studio;

import org.hyzionstudios.mysticquests.studio.StudioAudio;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig.StudioIdentityConfig;
import org.hyzionstudios.mysticquests.studio.StudioOidc;
import org.hyzionstudios.mysticquests.studio.StudioLive;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig.StudioConfig;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.QuestContentLoader;
import org.hyzionstudios.mysticquests.integration.narrative.NarrativeIntegration;
import org.hyzionstudios.mysticquests.narrative.diagnostic.Diagnostic;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.studio.ContentRoot;
import org.hyzionstudios.mysticquests.studio.StudioAudit;
import org.hyzionstudios.mysticquests.studio.StudioAuth;
import org.hyzionstudios.mysticquests.studio.StudioCapability;
import org.hyzionstudios.mysticquests.studio.StudioReleases;
import org.hyzionstudios.mysticquests.studio.StudioService;
import org.hyzionstudios.mysticquests.studio.StudioValidation;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace;
import org.hyzionstudios.mysticquests.studio.web.StudioHttpServer;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.permissions.PermissionsModule;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import java.util.logging.Level;

/**
 * Starts the web Creator Studio (§11) when {@code studio.enabled} is set: the Studio services over
 * the live packages folder, validated with the same loader and narrative compiler a reload uses,
 * permissions from the server's permission module, and the web server.
 *
 * <p>Off by default. When the JVM lacks the {@code jdk.httpserver} module, the Studio stays off and
 * says so, and nothing else is affected.
 */
public final class StudioIntegration implements AutoCloseable {
    private static final String LOG_PREFIX = "[MysticQuests Studio] ";

    private final StudioService studio;
    private final StudioHttpServer server;

    private StudioIntegration(StudioService studio, StudioHttpServer server) {
        this.studio = studio;
        this.server = server;
    }

    /** A reload that throws with the server's reasons when it refuses the content. */
    @FunctionalInterface
    public interface Reload {
        void reload() throws IOException;
    }

    public static Optional<StudioIntegration> start(StudioConfig config, Path dataDirectory, Path packages, ObjectMapper mapper,
                                                    Supplier<NarrativeIntegration> narrative, Reload reload, StudioLive live,
                                                    HytaleLogger logger) {
        if (!config.enabled()) {
            return Optional.empty();
        }
        try {
            Class.forName("com.sun.net.httpserver.HttpServer", false, StudioIntegration.class.getClassLoader());
        } catch (ClassNotFoundException missing) {
            logger.at(Level.WARNING).log(LOG_PREFIX + "this Java runtime has no jdk.httpserver module, so the web Studio stays off.");
            return Optional.empty();
        }
        Clock clock = Clock.systemUTC();
        Path root = dataDirectory.resolve("studio");
        ObjectMapper json = new ObjectMapper();
        StudioWorkspace workspace = new StudioWorkspace(ContentRoot.live(packages), root, json, Json.createYamlMapper());
        StudioValidation.Validator validator = draft -> validate(draft, mapper, narrative.get());
        StudioReleases releases = new StudioReleases(root, workspace, validator, reload::reload, clock, json);
        StudioAudit audit = new StudioAudit(root.resolve("audit.jsonl"), clock, json,
                line -> logger.at(Level.INFO).log(LOG_PREFIX + line));
        StudioCapability.Permissions permissions = (player, permission) -> PermissionsModule.get().hasPermission(player, permission);
        StudioService studio = new StudioService(new StudioAuth(clock), workspace, releases, audit, permissions, validator,
                config.environment(), live, clock);
        try {
            StudioHttpServer server = new StudioHttpServer(studio, config.bind(), config.port(), config.publicUrl(),
                    problem -> logger.at(Level.WARNING).log(LOG_PREFIX + problem));
            identitySignIn(config, server.publicUrl(), clock, logger).ifPresent(studio::identitySignIn);
            // The generated sound pack sits beside this plugin's folder in mods/, where packs are found.
            studio.audio(new StudioAudio(root, json, clock), dataDirectory.resolveSibling(StudioAudio.PACK_FOLDER), serverVersion());
            server.start();
            logger.at(Level.INFO).log(LOG_PREFIX + "listening on " + config.bind() + ":" + config.port() + "; open " + server.publicUrl()
                    + " and sign in with /mquest studio login (" + config.environment() + ").");
            return Optional.of(new StudioIntegration(studio, server));
        } catch (IOException | RuntimeException failure) {
            logger.at(Level.SEVERE).log(LOG_PREFIX + "could not listen on " + config.bind() + ":" + config.port() + ": " + failure.getMessage());
            return Optional.empty();
        }
    }

    /**
     * Checks a draft exactly as a reload would, without installing anything.
     *
     * @param narrative the story compiler; null checks the v1 content only
     */
    public static StudioValidation validate(ContentRoot draft, ObjectMapper mapper, @Nullable NarrativeIntegration narrative) {
        LoadedContent loaded;
        try {
            loaded = new QuestContentLoader(mapper).load(draft.packages());
        } catch (IOException refused) {
            List<StudioValidation.Problem> problems = new ArrayList<>();
            for (String line : String.valueOf(refused.getMessage()).split("\n")) {
                String text = line.strip();
                if (text.startsWith("- ")) {
                    problems.add(new StudioValidation.Problem("error", "CONTENT", "", text.substring(2)));
                }
            }
            if (problems.isEmpty()) {
                problems.add(new StudioValidation.Problem("error", "CONTENT", "", String.valueOf(refused.getMessage())));
            }
            return new StudioValidation(false, 0, 0, problems);
        }
        DiagnosticReport report = narrative == null ? new DiagnosticReport() : narrative.check(loaded);
        List<StudioValidation.Problem> problems = new ArrayList<>();
        for (Diagnostic diagnostic : report.all()) {
            problems.add(new StudioValidation.Problem(diagnostic.severity().name().toLowerCase(Locale.ROOT), diagnostic.code().name(),
                    diagnostic.path(), diagnostic.hint() == null ? diagnostic.message() : diagnostic.message() + " (" + diagnostic.hint() + ")"));
        }
        return new StudioValidation(!report.hasErrors(), loaded.packages().size(), loaded.quests().size(), problems);
    }

    /** MysticIdentity sign-in, when configured completely; anything missing is logged and leaves codes only. */
    private static Optional<StudioOidc> identitySignIn(StudioConfig config, String publicUrl, Clock clock, HytaleLogger logger) {
        StudioIdentityConfig identity = config.identity();
        if (!identity.enabled()) {
            return Optional.empty();
        }
        String secret = System.getenv(identity.clientSecretEnv());
        if (identity.issuer().isBlank() || identity.clientId().isBlank() || secret == null || secret.isBlank()) {
            logger.at(Level.WARNING).log(LOG_PREFIX + "MysticIdentity sign-in is on but incomplete: set studio.identity.issuer, "
                    + "studio.identity.clientId and the " + identity.clientSecretEnv() + " environment variable. In-game codes still work.");
            return Optional.empty();
        }
        String redirect = publicUrl + "auth/callback";
        logger.at(Level.INFO).log(LOG_PREFIX + "MysticIdentity sign-in through " + identity.issuer() + "; register " + redirect
                + " as the client's redirect.");
        return Optional.of(new StudioOidc(new StudioOidc.Settings(identity.issuer(), identity.clientId(), secret, redirect),
                StudioOidc.http(), clock));
    }

    /** The engine range this plugin's manifest declares, so the generated pack loads where the plugin does. */
    private static String serverVersion() {
        try (java.io.InputStream in = StudioIntegration.class.getResourceAsStream("/manifest.json")) {
            return in == null ? "*" : new ObjectMapper().readTree(in).path("ServerVersion").asText("*");
        } catch (IOException unreadable) {
            return "*";
        }
    }

    /** A one-time sign-in code for a player, and the address to open. */
    public record Login(String code, String url) {
    }

    public Login login(UUID player, String name) {
        String code = studio.auth().issueCode(player, name);
        return new Login(code, server.publicUrl() + "#code=" + code);
    }

    public boolean mayLogin(UUID player) {
        return StudioCapability.LOGIN.grantedTo(player, (id, permission) -> PermissionsModule.get().hasPermission(id, permission));
    }

    public int revoke(UUID player) {
        return studio.auth().revoke(player);
    }

    public int activeSessions() {
        return studio.auth().activeSessions();
    }

    public int liveObservers() {
        return studio.liveObservers();
    }

    public String url() {
        return server.publicUrl();
    }

    @Override
    public void close() {
        server.close();
    }
}
