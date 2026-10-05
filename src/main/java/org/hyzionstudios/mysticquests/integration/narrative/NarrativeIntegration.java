package org.hyzionstudios.mysticquests.integration.narrative;

import org.hyzionstudios.mysticquests.integration.mysticidentity.IdentityAccounts;
import org.hyzionstudios.mysticquests.api.MysticQuestsRegistry;
import org.hyzionstudios.mysticquests.config.MysticQuestsConfig;
import org.hyzionstudios.mysticquests.content.LoadedContent;
import org.hyzionstudios.mysticquests.content.PackageMetadata;
import org.hyzionstudios.mysticquests.integration.MysticGenerationBridge;
import org.hyzionstudios.mysticquests.integration.MysticPartyIntegration;
import org.hyzionstudios.mysticquests.integration.triggervolumes.NarrativeTriggerBridge;
import org.hyzionstudios.mysticquests.narrative.NarrativeContent;
import org.hyzionstudios.mysticquests.narrative.NarrativeRuntime;
import org.hyzionstudios.mysticquests.model.ConversationDefinition;
import org.hyzionstudios.mysticquests.model.ConversationNode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaAsset;
import org.hyzionstudios.mysticquests.narrative.media.MediaKind;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink;
import org.hyzionstudios.mysticquests.narrative.media.Spatial;
import org.hyzionstudios.mysticquests.narrative.persistence.JsonDocumentStore;
import org.hyzionstudios.mysticquests.narrative.session.PartyExitPolicy;
import org.hyzionstudios.mysticquests.narrative.state.ScopeSupport;
import org.hyzionstudios.mysticquests.narrative.trigger.QuestTriggerService.Routed;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerEvent;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;
import org.hyzionstudios.mysticquests.service.PlayerQuestService;
import org.hyzionstudios.mysticquests.service.PlayerSessionService;
import org.hyzionstudios.mysticquests.service.QuestSignal;
import org.hyzionstudios.mysticquests.service.QuestSignalBus;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.io.IOException;
import java.nio.file.Path;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.logging.Level;

/**
 * Wires the engine-free {@link NarrativeRuntime} into the running server: storage location, party
 * provider, v1 bridges, trigger volumes, player lifecycle and content reload.
 *
 * <p>Content reload stays transactional across both runtimes. {@link #compile} runs before the v1
 * content is swapped in, and any narrative error fails the whole reload, so the server never
 * runs new quests against old puzzles or the other way round.
 */
public final class NarrativeIntegration implements AutoCloseable {
    private static final String LOG_PREFIX = "[MysticQuests narrative] ";

    private final NarrativeRuntime narrative;
    private final MysticPartyIntegration parties;
    private final PlayerSessionService sessions;
    private final HytaleLogger logger;
    private final HytaleMedia media;
    private final QuestScripts scripts;

    public NarrativeIntegration(
            Path dataDirectory,
            MysticQuestsConfig.NarrativeConfig config,
            ObjectMapper mapper,
            MysticPartyIntegration parties,
            PlayerQuestService quests,
            QuestSignalBus signals,
            MysticQuestsRegistry registry,
            PlayerSessionService sessions,
            MysticGenerationBridge generation,
            HytaleLogger logger) throws IOException {
        this.parties = parties;
        this.sessions = sessions;
        this.logger = logger;
        PartyExitPolicy exitPolicy = exitPolicy(config.partyExitPolicy());
        this.narrative = new NarrativeRuntime(new NarrativeRuntime.Settings(
                new JsonDocumentStore(dataDirectory.resolve(config.dataPath()), mapper),
                Clock.systemUTC(),
                config.serverId(),
                "network",
                ScopeSupport.standard(parties.available(), IdentityAccounts.present()),
                parties::partyId,
                IdentityAccounts.resolver(),
                config.openNamespaces(),
                (player, signal, amount) -> signals.publish(QuestSignal.simple(player, "signal", signal.toString(), amount)),
                exitPolicy,
                config.flushIntervalMillis(),
                problem -> logger.at(Level.WARNING).log(LOG_PREFIX + problem),
                audit -> logger.at(Level.INFO).log(LOG_PREFIX + audit),
                config.limits()));
        LegacyBridge.register(narrative, () -> quests, registry, player -> sessions.playerRef(player) != null);
        StoryEntityActions.register(narrative, generation, sessions,
                problem -> logger.at(Level.WARNING).log(LOG_PREFIX + problem));
        parties.addLifecycleListener(new MysticPartyIntegration.LifecycleListener() {
            @Override
            public void memberLeft(String partyId, UUID playerId) {
                narrative.onPartyMemberLeft(partyId, playerId);
            }

            @Override
            public void disbanded(String partyId, Set<UUID> members) {
                narrative.onPartyDisbanded(partyId, members);
            }
        });
        NarrativeTriggerBridge.bind(narrative, logger);
        // Bound before the first compile, so voice lines are checked against the loaded sound assets.
        this.media = new HytaleMedia(narrative, sessions, config.subtitles(),
                problem -> logger.at(Level.WARNING).log(LOG_PREFIX + problem));
        narrative.media().bind(media, media, config.fallbackLocale());
        // v1 quests and conversations run 2.0 script through the "narrative" event and condition.
        this.scripts = new QuestScripts(narrative, problem -> logger.at(Level.WARNING).log(LOG_PREFIX + problem));
        quests.bindNarrativeScripts(scripts);
    }

    public NarrativeRuntime runtime() {
        return narrative;
    }

    /**
     * Compiles the narrative sections of a freshly loaded content set, without installing them.
     *
     * @throws IOException listing every error, so the caller can keep the previous content
     */
    public NarrativeContent compile(LoadedContent content) throws IOException {
        DiagnosticReport report = new DiagnosticReport();
        NarrativeContent compiled = compileInto(content, report);
        if (report.hasErrors()) {
            throw new IOException("MysticQuests narrative validation failed:\n - "
                    + String.join("\n - ", report.errors().stream().map(Object::toString).toList()));
        }
        report.warnings().forEach(warning -> logger.at(Level.WARNING).log(LOG_PREFIX + warning));
        return compiled;
    }

    /**
     * Every problem a reload of {@code content} would report, without installing anything; the web
     * Studio validates drafts with this, so a draft that passes is one the server accepts.
     */
    public DiagnosticReport check(LoadedContent content) {
        DiagnosticReport report = new DiagnosticReport();
        compileInto(content, report);
        return report;
    }

    private NarrativeContent compileInto(LoadedContent content, DiagnosticReport report) {
        Map<String, String> versions = new LinkedHashMap<>();
        for (Map.Entry<String, PackageMetadata> entry : content.packageMetadata().entrySet()) {
            versions.put(entry.getKey(), entry.getValue().version());
        }
        NarrativeContent compiled = narrative.compile(content.narrativeSections(), versions, report);
        checkConversationVoices(content, compiled, report);
        QuestScripts.validate(content, compiled, narrative::compileContext, report);
        return compiled;
    }

    /** A v1 dialogue node's {@code voice} must name a voice-capable medium in the same release. */
    private static void checkConversationVoices(LoadedContent content, NarrativeContent compiled, DiagnosticReport report) {
        for (ConversationDefinition conversation : content.conversations().values()) {
            for (ConversationNode node : conversation.nodes()) {
                if (node.voice() == null) {
                    continue;
                }
                String path = conversation.packageId() + "/conversations<" + conversation.id() + ">." + node.id();
                MediaAsset asset = NamespacedId.tryParse(node.voice()).map(compiled.media()::get).orElse(null);
                if (asset == null) {
                    report.error(DiagnosticCode.MISSING_REFERENCE, path, "voice names unknown media '" + node.voice() + "'");
                } else if (asset.kind() == MediaKind.MUSIC || asset.spatial() == Spatial.POSITION) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, path, "voice " + asset.id()
                            + " must be a 2d or entity sound; dialogue plays it to the speaker's listener");
                }
            }
        }
    }

    /**
     * Plays a dialogue node's voice line to the one player reading it. Subtitles are left off because
     * the conversation page already shows the text; an entity line follows the NPC being talked to.
     */
    public void playVoice(UUID player, String voice, @Nullable String speakerEntity) {
        MediaAsset asset = NamespacedId.tryParse(voice).map(narrative.media()::asset).orElse(null);
        if (asset == null) {
            return;
        }
        MediaSink.Placement placement = MediaSink.Placement.HEAD;
        if (asset.spatial() == Spatial.ENTITY && speakerEntity != null) {
            try {
                placement = MediaSink.Placement.on(new EntityRefValue("uuid", UUID.fromString(speakerEntity).toString()));
            } catch (IllegalArgumentException notUuid) {
                placement = MediaSink.Placement.HEAD;
            }
        }
        narrative.media().play(List.of(player), asset, placement, false);
    }

    public void install(NarrativeContent compiled) {
        narrative.install(compiled);
        scripts.clear();
        logger.at(Level.INFO).log(LOG_PREFIX + "Loaded " + compiled.puzzles().size() + " puzzles, "
                + compiled.schemas().variables().size() + " variables, " + compiled.schemas().tags().size() + " tags.");
    }

    public void onJoin(UUID player) {
        narrative.onJoin(player);
    }

    public void onQuit(UUID player) {
        Optional<String> party = parties.partyId(player);
        boolean partyStillOnline = parties.members(player).stream()
                .anyMatch(member -> !member.equals(player) && sessions.playerRef(member) != null);
        narrative.onQuit(player, party, partyStillOnline);
    }

    /**
     * Routes a trigger volume event through logical activation and puzzle bindings.
     *
     * @return false when the volume is logically disabled for this player, in which case v1
     *         objective signals must not fire for it either
     */
    public boolean onTrigger(String world, String volumeId, String eventType, UUID entity) {
        UUID player = sessions.playerRef(entity) != null ? entity : null;
        TriggerEvent event = new TriggerEvent(TriggerEvent.volumeKey(world, volumeId), eventType, player, entity, List.of());
        Routed routed = narrative.triggers().handle(event, world == null || world.isBlank() ? null : world);
        return routed.enabled();
    }

    @Override
    public void close() {
        media.close();
        NarrativeTriggerBridge.bind(null, null);
        narrative.close();
    }

    private PartyExitPolicy exitPolicy(@Nullable String raw) {
        try {
            return PartyExitPolicy.valueOf(raw == null ? "FORK" : raw.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException unknown) {
            logger.at(Level.WARNING).log(LOG_PREFIX + "Unknown narrative.partyExitPolicy '" + raw + "'; using fork.");
            return PartyExitPolicy.FORK;
        }
    }
}
