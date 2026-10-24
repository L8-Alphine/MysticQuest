package org.hyzionstudios.mysticquests.config;

import org.hyzionstudios.mysticquests.narrative.NarrativeLimits;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;

public record MysticQuestsConfig(
        StorageConfig storage,
        String packagesPath,
        IntegrationConfig integrations,
        StateConfig state,
        UiConfig ui,
        boolean debug,
        NarrativeConfig narrative,
        StudioConfig studio,
        ExternalQuestsConfig externalQuests,
        DialogueConfig dialogue,
        TrialsConfig trials) {
    public static MysticQuestsConfig defaults() {
        return new MysticQuestsConfig(
                new StorageConfig("sqlite", "data/players", "data/mysticquests.db"),
                "packages",
                new IntegrationConfig(true, true, true, true, true, true, true, true),
                new StateConfig(true, true, StateConfig.DEFAULT_SAVE_INTERVAL_MILLIS),
                new UiConfig(true, UiConfig.DEFAULT_HUD_JOIN_DELAY_MILLIS, false),
                false,
                NarrativeConfig.DEFAULTS,
                StudioConfig.DEFAULTS,
                ExternalQuestsConfig.DEFAULTS,
                DialogueConfig.DEFAULTS,
                TrialsConfig.DEFAULTS);
    }

    public static MysticQuestsConfig load(Path dataDirectory, ObjectMapper mapper) throws IOException {
        Path configPath = dataDirectory.resolve("config.json");
        if (Files.notExists(configPath)) {
            MysticQuestsConfig defaults = defaults();
            Files.createDirectories(dataDirectory);
            mapper.writerWithDefaultPrettyPrinter().writeValue(configPath.toFile(), defaults);
            return defaults;
        }
        MysticQuestsConfig config = mapper.readValue(configPath.toFile(), MysticQuestsConfig.class);
        return config.withDefaults();
    }

    private MysticQuestsConfig withDefaults() {
        MysticQuestsConfig defaults = defaults();
        return new MysticQuestsConfig(
                storage == null ? defaults.storage : storage.withDefaults(),
                packagesPath == null || packagesPath.isBlank() ? defaults.packagesPath : packagesPath,
                integrations == null ? defaults.integrations : integrations.withDefaults(),
                state == null ? defaults.state : state.withDefaults(),
                ui == null ? defaults.ui : ui.withDefaults(),
                debug,
                narrative == null ? defaults.narrative : narrative.withDefaults(),
                studio == null ? defaults.studio : studio.withDefaults(),
                externalQuests == null ? defaults.externalQuests : externalQuests.withDefaults(),
                dialogue == null ? defaults.dialogue : dialogue.withDefaults(),
                trials == null ? defaults.trials : trials.withDefaults());
    }

    /**
     * Trials other mods start (MysticRPG class, era and recovery trials).
     *
     * @param network the shared-SQL trial store (MysticRPG PO addendum 2026-10-24, R-1)
     */
    public record TrialsConfig(NetworkTrialsConfig network) {
        static final TrialsConfig DEFAULTS = new TrialsConfig(NetworkTrialsConfig.DEFAULTS);

        TrialsConfig withDefaults() {
            return new TrialsConfig(network == null ? NetworkTrialsConfig.DEFAULTS : network.withDefaults());
        }
    }

    /**
     * Shared-SQL trial persistence for a network of servers (R-1, docs/integrations/network-trials.md).
     * Off by default: a standalone server keeps trials in {@code data/trials.json} and other mods refuse
     * to transfer a player during a trial. On, every server of one realm points at the same database and
     * a trial run follows its player across servers (one server runs it at a time, fenced by a lease and
     * a revision).
     *
     * @param enabled          use the shared store; when it cannot be reached at start the trial service
     *                         stays local and reports why ({@code /mq trials network})
     * @param jdbcUrl          {@code jdbc:mariadb:}, {@code jdbc:mysql:}, {@code jdbc:postgresql:} or
     *                         {@code jdbc:h2:} (H2 for development; its driver is not bundled)
     * @param username         database user
     * @param passwordEnv      environment variable holding the password (preferred)
     * @param password         the password itself when no environment variable is set
     * @param realmId          the realm this server belongs to, {@code namespace:name}; must equal
     *                         MysticRPG's realm id. Trials never cross realms
     * @param serverId         this server's id in the network; must equal MysticRPG's server id
     * @param tablePrefix      prefix of the store's tables: a letter, then letters, digits or underscores
     * @param leaseSeconds     how long a server's claim on a run lasts without renewal (10-300)
     * @param handoffSeconds   how long a run waits for its player on another server after they leave
     *                         (15-1800); past it the run is abandoned
     * @param peerStaleSeconds a server whose heartbeat is older than this is not a valid transfer target
     *                         (15-600)
     * @param migration        {@code apply} (create or upgrade the schema at start) or {@code dry-run}
     *                         (log the statements, apply nothing and stay local)
     */
    public record NetworkTrialsConfig(
            Boolean enabled,
            String jdbcUrl,
            String username,
            String passwordEnv,
            String password,
            String realmId,
            String serverId,
            String tablePrefix,
            Integer leaseSeconds,
            Integer handoffSeconds,
            Integer peerStaleSeconds,
            String migration) {
        public static final NetworkTrialsConfig DEFAULTS = new NetworkTrialsConfig(false, "", "", "", "", "", "",
                "mq_", 30, 120, 45, "apply");

        public NetworkTrialsConfig withDefaults() {
            return new NetworkTrialsConfig(
                    enabled != null && enabled,
                    jdbcUrl == null ? "" : jdbcUrl.strip(),
                    username == null ? "" : username.strip(),
                    passwordEnv == null ? "" : passwordEnv.strip(),
                    password == null ? "" : password,
                    realmId == null ? "" : realmId.strip(),
                    serverId == null ? "" : serverId.strip(),
                    tablePrefix == null || tablePrefix.isBlank() ? DEFAULTS.tablePrefix : tablePrefix.strip(),
                    leaseSeconds == null ? DEFAULTS.leaseSeconds : leaseSeconds,
                    handoffSeconds == null ? DEFAULTS.handoffSeconds : handoffSeconds,
                    peerStaleSeconds == null ? DEFAULTS.peerStaleSeconds : peerStaleSeconds,
                    migration == null || migration.isBlank() ? DEFAULTS.migration
                            : migration.strip().toLowerCase(java.util.Locale.ROOT));
        }

        public boolean on() {
            return enabled != null && enabled;
        }

        /** Every problem with these settings when enabled; empty when usable. Nothing is clamped. */
        public List<String> problems() {
            List<String> problems = new java.util.ArrayList<>();
            if (!on()) {
                return problems;
            }
            String url = jdbcUrl == null ? "" : jdbcUrl;
            if (!(url.startsWith("jdbc:mariadb:") || url.startsWith("jdbc:mysql:")
                    || url.startsWith("jdbc:postgresql:") || url.startsWith("jdbc:h2:"))) {
                problems.add("trials.network.jdbcUrl must start with jdbc:mariadb:, jdbc:mysql:, jdbc:postgresql: or jdbc:h2:");
            }
            if (realmId == null || realmId.length() > 64
                    || !realmId.matches("[a-z0-9][a-z0-9._-]*:[a-z0-9][a-z0-9._-]*")) {
                problems.add("trials.network.realmId must be a namespace:name realm id (lower case, at most 64 "
                        + "characters), the same as MysticRPG's");
            }
            if (serverId == null || serverId.isBlank() || serverId.length() > 64) {
                problems.add("trials.network.serverId must be set (at most 64 characters), the same as MysticRPG's server id");
            }
            if (tablePrefix == null || !tablePrefix.matches("[A-Za-z][A-Za-z0-9_]{0,15}")) {
                problems.add("trials.network.tablePrefix must be a letter then up to 15 letters, digits or underscores");
            }
            if (leaseSeconds == null || leaseSeconds < 10 || leaseSeconds > 300) {
                problems.add("trials.network.leaseSeconds must be from 10 to 300");
            }
            if (handoffSeconds == null || handoffSeconds < 15 || handoffSeconds > 1800) {
                problems.add("trials.network.handoffSeconds must be from 15 to 1800");
            }
            if (peerStaleSeconds == null || peerStaleSeconds < 15 || peerStaleSeconds > 600) {
                problems.add("trials.network.peerStaleSeconds must be from 15 to 600");
            }
            if (!"apply".equals(migration) && !"dry-run".equals(migration)) {
                problems.add("trials.network.migration must be apply or dry-run");
            }
            return problems;
        }

        /** The password: the environment variable when named and set, else {@link #password}. */
        public String resolvePassword() {
            if (passwordEnv != null && !passwordEnv.isBlank()) {
                String fromEnv = System.getenv(passwordEnv);
                if (fromEnv != null) {
                    return fromEnv;
                }
            }
            return password == null ? "" : password;
        }
    }

    /**
     * The NPC dialogue screen.
     *
     * @param livePreview show the speaking NPC's real 3D model, turning slowly, in the dialogue's
     *         character card. Hytale's client only draws that preview while it believes it is in
     *         Creative, so while the card is open MysticQuests tells that one client it is in
     *         Creative (the server keeps the real mode and enforces it) and hands the real mode back
     *         when the card closes. Players already in Creative are never touched. Off, the card
     *         shows the model's portrait image instead.
     */
    public record DialogueConfig(Boolean livePreview) {
        static final DialogueConfig DEFAULTS = new DialogueConfig(true);

        DialogueConfig withDefaults() {
            return new DialogueConfig(livePreview == null || livePreview);
        }
    }

    /**
     * Quests that other mods run, shown and tracked through MysticQuests (docs/integrations/external-quests.md).
     * The other mod stays in charge of its quests: it decides progress and pays rewards; MysticQuests
     * mirrors them into its Journal and HUD and lets content react to them.
     *
     * @param endgame mirror Endgame &amp; QoL's story, side quests and contracts
     * @param ziggfreed mirror ZiggfreedCommon quests — MMOSkillTree's and its packs' — and MMOSkillTree
     *         skill levels for the {@code mmo:skillLevel} condition
     * @param unifiedTracker let the MysticQuests tracker show a mirrored quest. When a player tracks a
     *         quest in the other mod's own book, the tracker follows it
     * @param hideModTrackers hide the other mods' own quest tracker HUDs for mirrored mods, so the
     *         top-right corner has one tracker. Only applies while the MysticQuests HUD and the unified
     *         tracker are on, so nobody is left without one
     * @param hiddenHuds further custom HUD keys, from any mod, that players never see. A trailing
     *         {@code *} matches a prefix ({@code EndgameQoL.*}). {@code /mq hud keys} lists the keys a
     *         player has
     * @param forwardSignals publish the other mods' moments (boss kills, dungeon entries, quest
     *         completions, skill level-ups) as MysticQuests signals that {@code signal} objectives count
     * @param syncIntervalMillis how often a player's mirrored quests are re-read on their world thread
     */
    public record ExternalQuestsConfig(
            Boolean endgame,
            Boolean ziggfreed,
            Boolean unifiedTracker,
            Boolean hideModTrackers,
            List<String> hiddenHuds,
            Boolean forwardSignals,
            Integer syncIntervalMillis) {
        static final int DEFAULT_SYNC_INTERVAL_MILLIS = 1000;
        static final ExternalQuestsConfig DEFAULTS =
                new ExternalQuestsConfig(true, true, true, true, List.of(), true, DEFAULT_SYNC_INTERVAL_MILLIS);

        ExternalQuestsConfig withDefaults() {
            return new ExternalQuestsConfig(
                    endgame == null || endgame,
                    ziggfreed == null || ziggfreed,
                    unifiedTracker == null || unifiedTracker,
                    hideModTrackers == null || hideModTrackers,
                    hiddenHuds == null ? List.of() : hiddenHuds.stream().filter(key -> key != null && !key.isBlank()).map(String::strip).toList(),
                    forwardSignals == null || forwardSignals,
                    syncIntervalMillis == null || syncIntervalMillis < 250 ? DEFAULT_SYNC_INTERVAL_MILLIS : syncIntervalMillis);
        }
    }

    /**
     * The web Creator Studio (§11). Off by default.
     *
     * @param bind the address to listen on; keep {@code 127.0.0.1} unless a reverse proxy with HTTPS
     *         sits in front, and never expose plain HTTP to the internet
     * @param publicUrl the address creators open (for example {@code https://studio.example.net/});
     *         blank for {@code http://<bind>:<port>/}. Links and the origin check use it, and an
     *         {@code https} address makes the session cookie {@code Secure}
     * @param environment this server's publish target, shown in the Studio:
     *         {@code development}, {@code staging} or {@code production}
     */
    public record StudioConfig(Boolean enabled, String bind, Integer port, String publicUrl, String environment,
                               StudioIdentityConfig identity) {
        static final StudioConfig DEFAULTS = new StudioConfig(false, "127.0.0.1", 8765, "", "development", StudioIdentityConfig.DEFAULTS);

        StudioConfig withDefaults() {
            return new StudioConfig(
                    enabled != null && enabled,
                    bind == null || bind.isBlank() ? DEFAULTS.bind : bind,
                    port == null || port <= 0 || port > 65535 ? DEFAULTS.port : port,
                    publicUrl == null ? DEFAULTS.publicUrl : publicUrl.strip(),
                    environment == null || environment.isBlank() ? DEFAULTS.environment : environment.strip().toLowerCase(java.util.Locale.ROOT),
                    identity == null ? DEFAULTS.identity : identity.withDefaults());
        }
    }

    /**
     * Signing in to the Studio with MysticIdentity accounts (§19.2), beside in-game codes. Register
     * the Studio in MysticIdentity's Owner panel under Applications, with the redirect
     * {@code <studio publicUrl>auth/callback} and the scopes {@code openid identity.read hytale.read}.
     *
     * @param issuer MysticIdentity's public address, for example {@code https://id.example.net}
     * @param clientSecretEnv the environment variable holding the client secret; secrets are never
     *         read from this file
     */
    public record StudioIdentityConfig(Boolean enabled, String issuer, String clientId, String clientSecretEnv) {
        static final StudioIdentityConfig DEFAULTS = new StudioIdentityConfig(false, "", "", "MYSTICQUESTS_STUDIO_CLIENT_SECRET");

        StudioIdentityConfig withDefaults() {
            return new StudioIdentityConfig(
                    enabled != null && enabled,
                    issuer == null ? "" : issuer.strip(),
                    clientId == null ? "" : clientId.strip(),
                    clientSecretEnv == null || clientSecretEnv.isBlank() ? DEFAULTS.clientSecretEnv : clientSecretEnv.strip());
        }
    }

    /**
     * The 2.0 narrative runtime: sessions, typed state, logical trigger activation, puzzles.
     *
     * @param serverId this server's stable id. It owns server-scope state and global trigger
     *         overrides, and is recorded on every session it touches. Give each server on a network
     *         its own id, and never change it once players have state, or that state becomes
     *         unreachable.
     * @param dataPath where narrative documents are stored, relative to the data directory. Point
     *         several servers at one shared location to carry sessions across server transfers.
     * @param flushIntervalMillis how often pending narrative changes are written; quit and shutdown
     *         always write immediately
     * @param partyExitPolicy {@code fork} (a leaver keeps a solo copy of the party's stories) or
     *         {@code detach} (the story stays with the party)
     * @param openNamespaces namespaces where undeclared tags and variables are accepted, besides
     *         {@code legacy}. Meant for migration only: in an open namespace a typo creates a new
     *         variable instead of failing the reload.
     * @param maxStorySessions loaded story sessions above which a warning is logged; stories keep
     *         opening, because refusing one would strand a player mid-quest
     * @param maxStoryEntities the most story entities claimed at once; spawns and claims over it
     *         wait and retry until other stories release theirs
     * @param maxPuzzleInputs inputs per puzzle above which a reload warns
     */
    public record NarrativeConfig(
            String serverId,
            String dataPath,
            Integer flushIntervalMillis,
            String partyExitPolicy,
            List<String> openNamespaces,
            /* Where subtitles appear: chat, title or off. */
            String subtitles,
            /* The locale every localised voice line must have; heard when the player's own is missing. */
            String fallbackLocale,
            Integer maxStorySessions,
            Integer maxStoryEntities,
            Integer maxPuzzleInputs) {
        static final NarrativeConfig DEFAULTS = new NarrativeConfig("default", "data/narrative", 1000, "fork", List.of(), "chat", "en-US",
                NarrativeLimits.DEFAULTS.storySessions(), NarrativeLimits.DEFAULTS.storyEntities(), NarrativeLimits.DEFAULTS.puzzleInputs());

        NarrativeConfig withDefaults() {
            return new NarrativeConfig(
                    serverId == null || serverId.isBlank() ? DEFAULTS.serverId : serverId,
                    dataPath == null || dataPath.isBlank() ? DEFAULTS.dataPath : dataPath,
                    flushIntervalMillis == null || flushIntervalMillis <= 0 ? DEFAULTS.flushIntervalMillis : flushIntervalMillis,
                    partyExitPolicy == null || partyExitPolicy.isBlank() ? DEFAULTS.partyExitPolicy : partyExitPolicy,
                    openNamespaces == null ? DEFAULTS.openNamespaces : List.copyOf(openNamespaces),
                    subtitles == null || subtitles.isBlank() ? DEFAULTS.subtitles : subtitles,
                    fallbackLocale == null || fallbackLocale.isBlank() ? DEFAULTS.fallbackLocale : fallbackLocale,
                    positive(maxStorySessions, DEFAULTS.maxStorySessions),
                    positive(maxStoryEntities, DEFAULTS.maxStoryEntities),
                    positive(maxPuzzleInputs, DEFAULTS.maxPuzzleInputs));
        }

        public NarrativeLimits limits() {
            return new NarrativeLimits(maxStorySessions, maxStoryEntities, maxPuzzleInputs);
        }

        private static Integer positive(Integer value, Integer fallback) {
            return value == null || value <= 0 ? fallback : value;
        }
    }

    public record StorageConfig(String type, String jsonPath, String sqlitePath) {
        StorageConfig withDefaults() {
            StorageConfig defaults = defaults().storage();
            return new StorageConfig(
                    type == null || type.isBlank() ? defaults.type : type,
                    jsonPath == null || jsonPath.isBlank() ? defaults.jsonPath : jsonPath,
                    sqlitePath == null || sqlitePath.isBlank() ? defaults.sqlitePath : sqlitePath);
        }
    }

    /**
     * @param mysticGeneration whether quests may bind to NPCs authored in MysticGeneration's Studio.
     *         On by default and harmless when that mod is absent — the bridge simply never binds —
     *         so this exists to switch the integration off on a server that runs both mods but wants
     *         them kept apart.
     * @param mysticVanish whether quest visibility defers to MysticVanish. Both mods hide players
     *         through the same shared engine set, so with this off MysticQuests can lift a vanish it
     *         did not apply. On by default; turn it off only to reproduce that older behaviour.
     */
    public record IntegrationConfig(
            boolean vaultUnlocked,
            boolean placeholderApi,
            boolean mysticNameTags,
            boolean hyExtras,
            Boolean hyCitizens,
            Boolean hyExtrasExportPlayerState,
            Boolean mysticGeneration,
            Boolean mysticVanish) {
        IntegrationConfig withDefaults() {
            return new IntegrationConfig(
                    vaultUnlocked,
                    placeholderApi,
                    mysticNameTags,
                    hyExtras,
                    hyCitizens == null ? true : hyCitizens,
                    hyExtrasExportPlayerState == null ? true : hyExtrasExportPlayerState,
                    mysticGeneration == null ? true : mysticGeneration,
                    mysticVanish == null ? true : mysticVanish);
        }
    }

    /**
     * @param saveIntervalMillis how often pending tag and variable changes are flushed to storage.
     *         State mutations are coalesced per owner inside this window instead of writing on every
     *         change, so a quest that updates a counter every tick costs one write per window rather
     *         than one per tick. Disconnect and shutdown always flush synchronously regardless, so
     *         raising this trades crash-loss window for fewer writes, not correctness on clean stop.
     */
    public record StateConfig(
            boolean migrateLegacyPlayerTags,
            boolean migrateLegacyPlayerVariables,
            Integer saveIntervalMillis) {
        static final int DEFAULT_SAVE_INTERVAL_MILLIS = 1000;

        StateConfig withDefaults() {
            return new StateConfig(
                    migrateLegacyPlayerTags,
                    migrateLegacyPlayerVariables,
                    saveIntervalMillis == null || saveIntervalMillis <= 0
                            ? DEFAULT_SAVE_INTERVAL_MILLIS
                            : saveIntervalMillis);
        }
    }

    /**
     * @param questHud whether the pinned quest HUD is shown at all. Turn it off to keep players on
     *         the server while a "Could not find document …" disconnect is being diagnosed: a failed
     *         HUD append kicks the player, so a mod that cannot render its HUD is better off silent.
     * @param hudJoinDelayMillis how long after a player is ready before the quest HUD is pushed.
     *         A HUD sent on the ready tick itself arrives while the client is still registering the
     *         asset pack's UI documents, and the append fails with "Could not find document …" for a
     *         document the client already has on disk. Lower it if the HUD feels late; raise it if
     *         that disconnect comes back.
     * @param transitionCards whether MysticQuests announces quest accepted / next step / progress /
     *         complete with its own native notifications. Off by default: existing content usually
     *         sends its own {@code notification} events at those moments, and both would show.
     */
    public record UiConfig(Boolean questHud, Integer hudJoinDelayMillis, Boolean transitionCards) {
        static final int DEFAULT_HUD_JOIN_DELAY_MILLIS = 3000;

        UiConfig withDefaults() {
            return new UiConfig(
                    questHud == null || questHud,
                    hudJoinDelayMillis == null || hudJoinDelayMillis < 0
                            ? DEFAULT_HUD_JOIN_DELAY_MILLIS
                            : hudJoinDelayMillis,
                    transitionCards != null && transitionCards);
        }
    }
}
