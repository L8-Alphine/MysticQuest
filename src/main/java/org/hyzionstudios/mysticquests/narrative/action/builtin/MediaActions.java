package org.hyzionstudios.mysticquests.narrative.action.builtin;

import org.hyzionstudios.mysticquests.narrative.CompileContext;
import org.hyzionstudios.mysticquests.narrative.ContentParams;
import org.hyzionstudios.mysticquests.narrative.action.ActionContext;
import org.hyzionstudios.mysticquests.narrative.action.ActionHandler;
import org.hyzionstudios.mysticquests.narrative.action.ActionResult;
import org.hyzionstudios.mysticquests.narrative.action.ActionTypeRegistry;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.media.MediaAsset;
import org.hyzionstudios.mysticquests.narrative.media.MediaKind;
import org.hyzionstudios.mysticquests.narrative.media.MediaSink.Placement;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService.Audience;
import org.hyzionstudios.mysticquests.narrative.media.QuestMediaService.PlayReport;
import org.hyzionstudios.mysticquests.narrative.media.Spatial;
import org.hyzionstudios.mysticquests.narrative.state.QuestVariableService;
import org.hyzionstudios.mysticquests.narrative.trigger.TriggerScope;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.util.Map;

/**
 * Media actions (§13, §18).
 *
 * <pre>
 *   { "type": "mysticquests:media.play", "media": "hyzion:old_man.warning_001", "variable": "hyzion:druid_temple.old_man" }
 *   { "type": "mysticquests:media.play", "media": "hyzion:temple.rumble", "at": { "x": 10, "y": 64, "z": -3 }, "audience": "party" }
 *   { "type": "mysticquests:media.stop", "channel": "voice" }
 *   { "type": "mysticquests:music.set",  "music": "hyzion:temple.tension" }
 *   { "type": "mysticquests:music.clear" }
 * </pre>
 *
 * <p>{@code audience} is player, party, session, world or global, defaulting to the session when the
 * action runs in one and the player otherwise. Music {@code scope} works like trigger scope. Whether
 * the named media exists, and whether a positional or entity sound got a place, is checked after all
 * media in the reload have compiled.
 */
public final class MediaActions {
    public static final NamespacedId PLAY = NamespacedId.of("mysticquests", "media.play");
    public static final NamespacedId STOP = NamespacedId.of("mysticquests", "media.stop");
    public static final NamespacedId MUSIC_SET = NamespacedId.of("mysticquests", "music.set");
    public static final NamespacedId MUSIC_CLEAR = NamespacedId.of("mysticquests", "music.clear");

    private MediaActions() {
    }

    public static void register(ActionTypeRegistry registry, QuestMediaService media, QuestVariableService variables) {
        registry.registerBuiltIn(PLAY, new Play(media, variables));
        registry.registerBuiltIn(STOP, new Stop(media));
        registry.registerBuiltIn(MUSIC_SET, new Music(media, true));
        registry.registerBuiltIn(MUSIC_CLEAR, new Music(media, false));
    }

    static Audience audience(ActionContext context, ObjectNode parameters) {
        Audience audience = Audience.parse(parameters.path("audience").asText(null));
        if (audience != null) {
            return audience;
        }
        return context.scope().sessionId() != null ? Audience.STORY_SESSION : Audience.PLAYER;
    }

    static void validateAudience(ObjectNode parameters, String path, DiagnosticReport report) {
        if (parameters.hasNonNull("audience") && Audience.parse(parameters.get("audience").asText()) == null) {
            report.error(DiagnosticCode.INVALID_SCOPE, path, "unknown audience '" + parameters.get("audience").asText() + "'",
                    "use player, party, session, world or global; for an area, play at a position");
        }
    }

    private record Play(QuestMediaService media, QuestVariableService variables) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            NamespacedId id = ContentParams.id(parameters, "media");
            MediaAsset asset = media.asset(id);
            if (asset == null) {
                return ActionResult.terminal("media " + id + " is not loaded");
            }
            Placement placement;
            if (parameters.has("at")) {
                JsonNode at = parameters.get("at");
                placement = Placement.at(at.path("x").asDouble(), at.path("y").asDouble(), at.path("z").asDouble());
            } else if (parameters.hasNonNull("entity") || parameters.hasNonNull("variable")) {
                EntityRefValue entity = EntityActions.entity(context, parameters, variables);
                if (entity == null) {
                    return ActionResult.skipped("media.play: the speaking entity is not set");
                }
                placement = Placement.on(entity);
            } else {
                placement = Placement.HEAD;
            }
            PlayReport report = media.play(media.listeners(audience(context, parameters), context.scope()), asset, placement,
                    parameters.path("subtitles").asBoolean(true));
            return report.reached() == 0 && report.failed() == 0
                    ? ActionResult.skipped("media.play: nobody in the audience is online")
                    : ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            ContentParams.id(parameters, "media", path, report);
            validateAudience(parameters, path, report);
            boolean at = parameters.has("at");
            boolean entity = parameters.hasNonNull("entity") || parameters.hasNonNull("variable");
            if (at && entity) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "a sound plays \"at\" a position or on an entity, not both");
            } else if (at) {
                JsonNode position = parameters.get("at");
                if (!position.path("x").isNumber() || !position.path("y").isNumber() || !position.path("z").isNumber()) {
                    report.error(DiagnosticCode.INVALID_PARAMETER, path, "\"at\" needs numeric x, y and z");
                }
            } else if (entity) {
                EntityActions.validateEntity(parameters, path, context, report);
            }
        }
    }

    private record Stop(QuestMediaService media) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            String channel = parameters.path("channel").asText("voice");
            int stopped = media.stop(media.listeners(audience(context, parameters), context.scope()),
                    channel.equalsIgnoreCase("all") ? null : channel);
            return stopped == 0 ? ActionResult.skipped("media.stop: nothing was playing") : ActionResult.success();
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            validateAudience(parameters, path, report);
        }
    }

    private record Music(QuestMediaService media, boolean set) implements ActionHandler {
        @Override
        public ActionResult execute(ActionContext context, ObjectNode parameters) {
            TriggerScope scope = TriggerScope.parse(parameters.path("scope").asText(null));
            if (scope == null) {
                scope = context.scope().sessionId() != null ? TriggerScope.STORY_SESSION : TriggerScope.PLAYER;
            }
            String value = set ? parameters.path("music").asText() : null;
            return ActionResult.of(media.setMusic(scope, value, context.scope()));
        }

        @Override
        public void validate(ObjectNode parameters, String path, CompileContext context, DiagnosticReport report) {
            if (set && !parameters.path("music").asText("").equals(QuestMediaService.NO_MUSIC)) {
                ContentParams.id(parameters, "music", path, report);
            }
            if (parameters.hasNonNull("scope") && TriggerScope.parse(parameters.get("scope").asText()) == null) {
                report.error(DiagnosticCode.INVALID_SCOPE, path, "unknown music scope '" + parameters.get("scope").asText() + "'",
                        "use global, party, player or session");
            }
        }
    }

    /** Cross-checks one media action against the compiled media; run once every asset is known. */
    public static void checkReference(NamespacedId type, ObjectNode parameters, String path,
                                      Map<NamespacedId, MediaAsset> media, DiagnosticReport report) {
        if (type.equals(PLAY)) {
            MediaAsset asset = NamespacedId.tryParse(parameters.path("media").asText()).map(media::get).orElse(null);
            if (asset == null) {
                report.error(DiagnosticCode.MISSING_REFERENCE, path, "names unknown media '" + parameters.path("media").asText() + "'");
            } else if (asset.kind() == MediaKind.MUSIC) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "media " + asset.id() + " is music", "use mysticquests:music.set");
            } else if (asset.spatial() == Spatial.POSITION && !parameters.has("at")) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "media " + asset.id() + " is positional; give \"at\"");
            } else if (asset.spatial() == Spatial.ENTITY && !parameters.hasNonNull("entity") && !parameters.hasNonNull("variable")) {
                report.error(DiagnosticCode.INVALID_PARAMETER, path, "media " + asset.id() + " follows an entity; give \"entity\" or \"variable\"");
            }
        } else if (type.equals(MUSIC_SET)) {
            String raw = parameters.path("music").asText();
            if (raw.equals(QuestMediaService.NO_MUSIC)) {
                return;
            }
            MediaAsset asset = NamespacedId.tryParse(raw).map(media::get).orElse(null);
            if (asset == null || asset.kind() != MediaKind.MUSIC) {
                report.error(DiagnosticCode.MISSING_REFERENCE, path, "names unknown music '" + raw + "'",
                        "declare it under media with kind music, or use \"none\" for the world's own music");
            }
        }
    }
}
