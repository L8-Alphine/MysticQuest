package org.hyzionstudios.mysticquests.studio.web;

import org.hyzionstudios.mysticquests.studio.ContentRoot;
import org.hyzionstudios.mysticquests.studio.StudioAudio;
import org.hyzionstudios.mysticquests.studio.StudioAudioTestAccess;
import org.hyzionstudios.mysticquests.studio.StudioAudit;
import org.hyzionstudios.mysticquests.studio.StudioAuth;
import org.hyzionstudios.mysticquests.studio.StudioReleases;
import org.hyzionstudios.mysticquests.studio.StudioOidc;
import org.hyzionstudios.mysticquests.studio.StudioService;
import org.hyzionstudios.mysticquests.studio.StudioValidation;
import org.hyzionstudios.mysticquests.studio.StudioWorkspace;
import org.hyzionstudios.mysticquests.util.Json;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §23 at the HTTP boundary: cookies, CSRF, origin checks, headers and static files. */
final class StudioHttpServerTest {
    private static final UUID LEAD = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    @TempDir
    Path data;

    private final ObjectMapper json = new ObjectMapper();
    private final HttpClient client = HttpClient.newHttpClient();
    private StudioService studio;
    private StudioHttpServer server;
    private String base;

    @BeforeEach
    void setUp() throws IOException {
        Path live = data.resolve("packages");
        Files.createDirectories(live.resolve("greenvale"));
        Files.writeString(live.resolve("greenvale/quests.yml"), "quests:\n  - id: wolves\n    displayName: Wolf Trouble\n");
        Clock clock = Clock.systemUTC();
        Path root = data.resolve("studio");
        StudioWorkspace workspace = new StudioWorkspace(ContentRoot.live(live), root, json, Json.createYamlMapper());
        StudioValidation.Validator validator = draft -> new StudioValidation(true, 1, 1, List.of());
        StudioReleases releases = new StudioReleases(root, workspace, validator, () -> { }, clock, json);
        studio = new StudioService(new StudioAuth(clock), workspace, releases, new StudioAudit(root.resolve("audit.jsonl"), clock, json, line -> { }),
                (player, permission) -> true, validator, "development");
        studio.audio(new StudioAudio(root, json, clock), data.resolve(StudioAudio.PACK_FOLDER), "*");
        server = new StudioHttpServer(studio, "127.0.0.1", 0, "", line -> { });
        server.start();
        base = server.publicUrl();
    }

    @AfterEach
    void tearDown() {
        server.close();
    }

    private HttpResponse<String> send(HttpRequest.Builder request) throws IOException, InterruptedException {
        return client.send(request.build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpRequest.Builder request(String path) {
        return HttpRequest.newBuilder(URI.create(base + path.substring(1)));
    }

    @Test
    void aMysticIdentitySignInOnlyCompletesInTheBrowserThatStartedIt() throws Exception {
        String[] nonce = new String[1];
        studio.identitySignIn(new StudioOidc(new StudioOidc.Settings("https://id.example.net", "studio", "secret", base + "auth/callback"),
                new StudioOidc.Transport() {
                    @Override
                    public JsonNode get(URI uri, java.util.Map<String, String> headers) throws IOException {
                        return uri.getPath().endsWith("openid-configuration")
                                ? json.readTree("{\"issuer\":\"https://id.example.net\",\"authorization_endpoint\":\"https://id.example.net/authorize\","
                                        + "\"token_endpoint\":\"https://id.example.net/token\",\"userinfo_endpoint\":\"https://id.example.net/userinfo\"}")
                                : json.readTree("{\"sub\":\"1\",\"mystic\":{\"hytale\":{\"profile_uuid\":\"" + LEAD + "\",\"profile_username\":\"Lead\"}}}");
                    }

                    @Override
                    public JsonNode postForm(URI uri, java.util.Map<String, String> headers, java.util.Map<String, String> form) throws IOException {
                        String claims = "{\"iss\":\"https://id.example.net\",\"aud\":\"studio\",\"nonce\":\"" + nonce[0] + "\",\"exp\":"
                                + (System.currentTimeMillis() / 1000 + 300) + "}";
                        return json.readTree("{\"access_token\":\"t\",\"id_token\":\"e30." + java.util.Base64.getUrlEncoder().withoutPadding()
                                .encodeToString(claims.getBytes(java.nio.charset.StandardCharsets.UTF_8)) + ".s\"}");
                    }
                }, Clock.systemUTC()));

        HttpResponse<String> started = send(request("/auth/login"));
        assertEquals(302, started.statusCode());
        String location = started.headers().firstValue("Location").orElseThrow();
        java.util.Map<String, String> query = StudioHttpServer.query(URI.create(location));
        nonce[0] = query.get("nonce");
        String stateCookie = started.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(stateCookie.startsWith(StudioHttpServer.STATE_COOKIE + "=" + query.get("state")), stateCookie);
        String callback = "/auth/callback?state=" + query.get("state") + "&code=c1";

        HttpResponse<String> elsewhere = send(request(callback));
        assertTrue(elsewhere.headers().firstValue("Location").orElse("").startsWith("/#signin-error="),
                "a callback link opened in another browser does not sign it in");

        HttpResponse<String> mine = send(request(callback).header("Cookie", stateCookie.substring(0, stateCookie.indexOf(';'))));
        assertEquals("/", mine.headers().firstValue("Location").orElse(""), "nor did the refused attempt use up the flow");
        assertTrue(mine.headers().allValues("Set-Cookie").stream().anyMatch(cookie -> cookie.startsWith(StudioHttpServer.COOKIE + "=")));
    }

    @Test
    void thePageIsServedWithAStrictPolicyAndNothingOutsideItsFolder() throws Exception {
        HttpResponse<String> page = send(request("/"));
        assertEquals(200, page.statusCode());
        assertTrue(page.body().contains("MysticQuests Studio"));
        String csp = page.headers().firstValue("Content-Security-Policy").orElse("");
        assertTrue(csp.contains("script-src 'self'") && csp.contains("frame-ancestors 'none'"), csp);
        assertEquals("DENY", page.headers().firstValue("X-Frame-Options").orElse(""));
        assertEquals(200, send(request("/app.js")).statusCode());
        assertEquals(404, send(request("/../manifest.json")).statusCode());
        assertEquals(404, send(request("/studio-web/app.js")).statusCode(), "only plain names from the Studio folder");
    }

    @Test
    void signedInWritesNeedTheCsrfTokenAndASameSiteOrigin() throws Exception {
        assertEquals(401, send(request("/api/files")).statusCode(), "nothing without a session");

        String code = studio.auth().issueCode(LEAD, "lead");
        HttpResponse<String> login = send(request("/api/login").header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString("{\"code\":\"" + code + "\"}")));
        assertEquals(200, login.statusCode(), login.body());
        String cookie = login.headers().firstValue("Set-Cookie").orElseThrow();
        assertTrue(cookie.contains("HttpOnly") && cookie.contains("SameSite=Strict"), cookie);
        String session = cookie.substring(0, cookie.indexOf(';'));
        String csrf = json.readTree(login.body()).path("csrf").asText();

        assertEquals(200, send(request("/api/files").header("Cookie", session)).statusCode());
        String body = "{\"path\":\"packages/greenvale/notes.yml\",\"text\":\"quests: []\\n\",\"version\":null}";
        HttpResponse<String> noToken = send(request("/api/file").header("Cookie", session)
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, noToken.statusCode(), "a write without the CSRF token is refused");
        HttpResponse<String> foreign = send(request("/api/file").header("Cookie", session).header("X-CSRF-Token", csrf)
                .header("Origin", "https://evil.example").PUT(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(403, foreign.statusCode(), "a write from another site is refused");
        HttpResponse<String> saved = send(request("/api/file").header("Cookie", session).header("X-CSRF-Token", csrf)
                .PUT(HttpRequest.BodyPublishers.ofString(body)));
        assertEquals(200, saved.statusCode(), saved.body());
        assertTrue(Files.exists(data.resolve("studio/workspace/packages/greenvale/notes.yml")));

        JsonNode documents = json.readTree(send(request("/api/documents").header("Cookie", session)).body());
        assertTrue(documents.isArray() && documents.size() == 2, documents.toString());
        assertEquals("no-store", send(request("/api/me").header("Cookie", session)).headers().firstValue("Cache-Control").orElse(""));

        byte[] clip = StudioAudioTestAccess.vorbis();
        HttpResponse<String> uploaded = send(request("/api/audio?name=greeting&kind=voice").header("Cookie", session)
                .header("X-CSRF-Token", csrf).PUT(HttpRequest.BodyPublishers.ofByteArray(clip)));
        assertEquals(200, uploaded.statusCode(), uploaded.body());
        assertEquals("MysticQuests_Voice_greeting", json.readTree(uploaded.body()).path("clip").path("soundEvent").asText());
        HttpResponse<byte[]> played = client.send(request("/api/audio/file?name=greeting").header("Cookie", session).build(),
                HttpResponse.BodyHandlers.ofByteArray());
        assertEquals("audio/ogg", played.headers().firstValue("Content-Type").orElse(""));
        assertEquals(clip.length, played.body().length, "the preview player gets the master as uploaded");

        assertEquals(200, send(request("/api/logout").header("Cookie", session).header("X-CSRF-Token", csrf)
                .POST(HttpRequest.BodyPublishers.noBody())).statusCode());
        assertEquals(401, send(request("/api/files").header("Cookie", session)).statusCode(), "signed out means signed out");
    }
}
