package org.hyzionstudios.mysticquests.studio.web;

import java.util.UUID;
import org.hyzionstudios.mysticquests.studio.StudioException;
import org.hyzionstudios.mysticquests.studio.StudioService;

import java.util.Map;

/**
 * The Studio's JSON API. Each route is one {@link StudioService} call, which does the
 * authorization; this class only names the routes and reads their parameters.
 *
 * <pre>
 * POST   /api/login              { code }                  sign in with an in-game code
 * POST   /api/logout
 * GET    /api/sign-in-options                              which sign-ins this server offers (no session needed)
 * GET    /api/me                                           who is signed in, capabilities, CSRF token
 * GET    /api/files                                        draft files with versions
 * GET    /api/file?path=                                   one draft file
 * PUT    /api/file               { path, text, version }   save (version null creates)
 * DELETE /api/file?path=&amp;version=
 * GET    /api/documents                                    every draft file, parsed, for the form editors
 * GET    /api/document?path=
 * PUT    /api/document           { path, document, version }  save a form edit in the file's format
 * GET    /api/status                                       changes to publish, live edits made outside the Studio
 * POST   /api/validate                                     check the draft as a reload would
 * POST   /api/publish            { message, overwrite }
 * POST   /api/reset                                        discard the draft
 * GET    /api/releases                                     release history
 * GET    /api/releases/compare?from=&amp;to=
 * POST   /api/releases/restore   { number }                load a release into the draft
 * GET    /api/releases/export?number=                      a release as a zip, to promote to another server
 * PUT    /api/releases/import       (body: the zip)        load another server's release into the draft (needs edit)
 * GET    /api/audit?limit=
 * GET    /api/audio                                        uploaded clips with their SoundEvent ids
 * PUT    /api/audio?name=&amp;kind=   (body: the .ogg file)     upload or replace a clip (needs audio)
 * GET    /api/audio/file?name=                              a clip, for the preview player
 * DELETE /api/audio?name=
 * POST   /api/audio/build                                  write the generated sound pack (needs audio and publish)
 * GET    /api/live                                         online players and runtime metrics (needs live)
 * GET    /api/live/player?player=                          one player's story state, as /mq debug shows it
 * GET    /api/players/find?query=                          an online player by name, or a player by UUID (needs live)
 * GET    /api/players/state?player=                        a player's quests, tags, variables, story state and sessions (needs live)
 * POST   /api/players/change     { player, action, reason, ... }  an audited change to a player (needs players)
 * </pre>
 */
final class StudioApi {
    private StudioApi() {
    }

    static void register(StudioHttpServer server, StudioService studio) {
        server.route("POST", "/api/login", (session, request) -> studio.signIn(request.param("code"), request.address()));
        server.route("GET", "/api/sign-in-options", (session, request) -> studio.signInOptions());
        server.route("POST", "/api/logout", (session, request) -> {
            studio.signOut(session);
            return null;
        });
        server.route("GET", "/api/me", (session, request) -> studio.me(session));
        server.route("GET", "/api/files", (session, request) -> studio.files(session));
        server.route("GET", "/api/file", (session, request) -> studio.read(session, required(request, "path")));
        server.route("PUT", "/api/file", (session, request) -> Map.of("version",
                studio.write(session, required(request, "path"), required(request, "text"), request.param("version"))));
        server.route("DELETE", "/api/file", (session, request) -> {
            studio.delete(session, required(request, "path"), required(request, "version"));
            return null;
        });
        server.route("GET", "/api/documents", (session, request) -> studio.documents(session));
        server.route("GET", "/api/document", (session, request) -> studio.document(session, required(request, "path")));
        server.route("PUT", "/api/document", (session, request) -> {
            if (!request.body().path("document").isContainerNode()) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, "'document' must be an object or a list.");
            }
            return Map.of("version", studio.writeDocument(session, required(request, "path"), request.body().get("document"),
                    request.param("version")));
        });
        server.route("GET", "/api/status", (session, request) -> studio.status(session));
        server.route("POST", "/api/validate", (session, request) -> studio.validate(session));
        server.route("POST", "/api/publish", (session, request) ->
                studio.publish(session, request.param("message"), request.body().path("overwrite").asBoolean(false)));
        server.route("POST", "/api/reset", (session, request) -> {
            studio.reset(session);
            return null;
        });
        server.route("GET", "/api/releases", (session, request) -> studio.history(session));
        server.route("GET", "/api/releases/compare", (session, request) ->
                studio.compare(session, request.intParam("from"), request.intParam("to")));
        server.route("POST", "/api/releases/restore", (session, request) -> {
            studio.restore(session, request.intParam("number"));
            return null;
        });
        server.route("GET", "/api/audio", (session, request) -> studio.audioClips(session));
        server.route("PUT", "/api/audio", (session, request) ->
                studio.uploadAudio(session, required(request, "name"), required(request, "kind"), request.raw()));
        server.route("GET", "/api/audio/file", (session, request) ->
                new StudioHttpServer.Binary(studio.audioBytes(session, required(request, "name")), "audio/ogg", null));
        server.route("DELETE", "/api/audio", (session, request) -> {
            studio.deleteAudio(session, required(request, "name"));
            return null;
        });
        server.route("POST", "/api/audio/build", (session, request) -> studio.buildSoundPack(session));
        server.route("GET", "/api/live", (session, request) -> studio.liveOverview(session));
        server.route("GET", "/api/live/player", (session, request) -> {
            UUID player;
            try {
                player = UUID.fromString(required(request, "player"));
            } catch (IllegalArgumentException invalid) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, "'player' must be a player UUID.");
            }
            return studio.livePlayer(session, player);
        });
        server.route("GET", "/api/players/find", (session, request) -> studio.findPlayer(session, required(request, "query")));
        server.route("GET", "/api/players/state", (session, request) -> studio.playerState(session, player(request)));
        server.route("POST", "/api/players/change", (session, request) ->
                studio.changePlayer(session, player(request), request.body()));
        server.route("GET", "/api/releases/export", (session, request) -> {
            int number = request.intParam("number");
            return new StudioHttpServer.Binary(studio.exportRelease(session, number), "application/zip",
                    "mysticquests-" + studio.environment() + "-release-" + number + ".zip");
        });
        server.route("PUT", "/api/releases/import", (session, request) ->
                Map.of("files", studio.importBundle(session, request.raw())));
        server.route("GET", "/api/audit", (session, request) ->
                studio.auditTrail(session, request.query().containsKey("limit") ? request.intParam("limit") : 100));
    }

    private static UUID player(StudioHttpServer.Request request) throws StudioException {
        try {
            return UUID.fromString(required(request, "player"));
        } catch (IllegalArgumentException invalid) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "'player' must be a player UUID.");
        }
    }

    private static String required(StudioHttpServer.Request request, String name) throws StudioException {
        String value = request.param(name);
        if (value == null) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "'" + name + "' is required.");
        }
        return value;
    }
}
