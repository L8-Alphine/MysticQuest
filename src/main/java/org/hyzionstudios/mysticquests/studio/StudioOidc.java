package org.hyzionstudios.mysticquests.studio;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

import java.io.IOException;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.stream.Collectors;

/**
 * Studio sign-in with a MysticIdentity account (§19.2): OpenID Connect authorization code flow with
 * PKCE, against MysticIdentity's OpenID Provider.
 *
 * <p>The Studio asks for {@code openid identity.read hytale.read}; UserInfo then carries the linked
 * Hytale profile under {@code mystic.hytale.profile_uuid}, which is the player UUID the Studio's
 * permission checks use. An account with no linked Hytale profile cannot sign in, because there
 * would be no permissions to check.
 *
 * <p>The ID token comes straight from the token endpoint over TLS with client authentication, so
 * its signature is not checked (OIDC Core §3.1.3.7, item 6); its issuer, audience, expiry and nonce
 * are. Every flow is single-use, bound to its state, nonce and PKCE verifier, and expires after ten
 * minutes.
 */
public final class StudioOidc {
    static final Duration FLOW_TTL = Duration.ofMinutes(10);
    static final String SCOPES = "openid identity.read hytale.read";
    static final int MAX_PENDING = 1000;

    /** @param issuer MysticIdentity's public address, the issuer in its discovery document */
    public record Settings(String issuer, String clientId, String clientSecret, String redirectUri) {
    }

    /** Who signed in. */
    public record Identity(UUID player, String name) {
    }

    /**
     * A started sign-in: where to send the browser, and the state to bind to that browser (as a
     * cookie), so a callback link from someone else's sign-in cannot sign this browser in.
     */
    public record Start(URI authorize, String state) {
    }

    /** The HTTP calls the flow makes; replaced in tests. */
    public interface Transport {
        JsonNode get(URI uri, Map<String, String> headers) throws IOException;

        JsonNode postForm(URI uri, Map<String, String> headers, Map<String, String> form) throws IOException;
    }

    private record Pending(String verifier, String nonce, Instant expires) {
    }

    private record Discovery(String issuer, URI authorize, URI token, URI userinfo, Instant fetched) {
    }

    private final Settings settings;
    private final Transport transport;
    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> flows = new ConcurrentHashMap<>();
    private volatile Discovery discovery;

    public StudioOidc(Settings settings, Transport transport, Clock clock) {
        this.settings = settings;
        this.transport = transport;
        this.clock = clock;
    }

    /** Starts a new flow: the address to send the browser to, and the state to bind to it. */
    public Start begin() throws StudioException {
        Discovery endpoints = discovery();
        Instant now = clock.instant();
        flows.values().removeIf(flow -> now.isAfter(flow.expires()));
        // Anyone can start a flow, so pending ones are capped rather than left to grow.
        if (flows.size() >= MAX_PENDING) {
            throw new StudioException(StudioException.Status.TOO_MANY_REQUESTS, "Too many sign-ins are in progress. Try again in a few minutes.");
        }
        String state = random(24);
        String verifier = random(48);
        String nonce = random(24);
        flows.put(state, new Pending(verifier, nonce, now.plus(FLOW_TTL)));
        Map<String, String> query = new LinkedHashMap<>();
        query.put("response_type", "code");
        query.put("client_id", settings.clientId());
        query.put("redirect_uri", settings.redirectUri());
        query.put("scope", SCOPES);
        query.put("state", state);
        query.put("nonce", nonce);
        query.put("code_challenge", challenge(verifier));
        query.put("code_challenge_method", "S256");
        String separator = endpoints.authorize().getRawQuery() == null ? "?" : "&";
        return new Start(URI.create(endpoints.authorize() + separator + form(query)), state);
    }

    /**
     * Finishes a flow from the callback's {@code state} and {@code code}.
     *
     * @throws StudioException {@code UNAUTHORIZED} for an unknown, used or expired flow, a token that
     *         does not match it, or an account without a linked Hytale profile
     */
    public Identity complete(String state, String code) throws StudioException {
        Pending flow = state == null ? null : flows.remove(state);
        if (flow == null || clock.instant().isAfter(flow.expires())) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "That sign-in expired or was already used. Start again.");
        }
        if (code == null || code.isBlank()) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity did not sign you in.");
        }
        Discovery endpoints = discovery();
        JsonNode tokens;
        try {
            Map<String, String> form = new LinkedHashMap<>();
            form.put("grant_type", "authorization_code");
            form.put("code", code);
            form.put("redirect_uri", settings.redirectUri());
            form.put("code_verifier", flow.verifier());
            String basic = Base64.getEncoder().encodeToString((encode(settings.clientId()) + ":" + encode(settings.clientSecret()))
                    .getBytes(StandardCharsets.UTF_8));
            tokens = transport.postForm(endpoints.token(), Map.of("Authorization", "Basic " + basic), form);
        } catch (IOException failure) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity refused the sign-in: " + failure.getMessage());
        }
        String accessToken = tokens.path("access_token").asText("");
        if (accessToken.isEmpty()) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity returned no access token.");
        }
        checkIdToken(tokens.path("id_token").asText(""), endpoints, flow);
        JsonNode user;
        try {
            user = transport.get(endpoints.userinfo(), Map.of("Authorization", "Bearer " + accessToken));
        } catch (IOException failure) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "Could not read your MysticIdentity account: " + failure.getMessage());
        }
        JsonNode hytale = user.path("mystic").path("hytale");
        if (hytale.isMissingNode() || hytale.isNull()) {
            hytale = user.path("hytale");
        }
        String profile = hytale.path("profile_uuid").asText("");
        UUID player;
        try {
            player = UUID.fromString(profile);
        } catch (IllegalArgumentException missing) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED,
                    "Your MysticIdentity account has no linked Hytale profile. Link one, then sign in again.");
        }
        String name = hytale.path("profile_username").asText("");
        if (name.isBlank()) {
            name = user.path("preferred_username").asText(player.toString());
        }
        return new Identity(player, name);
    }

    private void checkIdToken(String idToken, Discovery endpoints, Pending flow) throws StudioException {
        String[] parts = idToken.split("\\.");
        if (parts.length != 3) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity returned no ID token; does the client allow 'openid'?");
        }
        JsonNode claims;
        try {
            claims = new ObjectMapper().readTree(Base64.getUrlDecoder().decode(parts[1]));
        } catch (IOException | IllegalArgumentException unreadable) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "The ID token could not be read.");
        }
        JsonNode audience = claims.path("aud");
        boolean forUs = audience.isArray()
                ? java.util.stream.StreamSupport.stream(audience.spliterator(), false).anyMatch(entry -> entry.asText().equals(settings.clientId()))
                : audience.asText().equals(settings.clientId());
        if (!claims.path("iss").asText().equals(endpoints.issuer()) || !forUs
                || !claims.path("nonce").asText().equals(flow.nonce())
                || clock.instant().getEpochSecond() > claims.path("exp").asLong(0)) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "The ID token is not for this sign-in.");
        }
    }

    private Discovery discovery() throws StudioException {
        Discovery cached = discovery;
        if (cached != null && clock.instant().isBefore(cached.fetched().plus(Duration.ofHours(1)))) {
            return cached;
        }
        String base = settings.issuer().endsWith("/") ? settings.issuer().substring(0, settings.issuer().length() - 1) : settings.issuer();
        try {
            JsonNode document = transport.get(URI.create(base + "/.well-known/openid-configuration"), Map.of());
            Discovery fresh = new Discovery(document.path("issuer").asText(base),
                    URI.create(document.path("authorization_endpoint").asText()),
                    URI.create(document.path("token_endpoint").asText()),
                    URI.create(document.path("userinfo_endpoint").asText()),
                    clock.instant());
            discovery = fresh;
            return fresh;
        } catch (IOException | IllegalArgumentException failure) {
            throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity is not reachable: " + failure.getMessage());
        }
    }

    private String random(int bytes) {
        byte[] buffer = new byte[bytes];
        random.nextBytes(buffer);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(buffer);
    }

    static String challenge(String verifier) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(verifier.getBytes(StandardCharsets.US_ASCII));
            return Base64.getUrlEncoder().withoutPadding().encodeToString(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException(impossible);
        }
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8);
    }

    static String form(Map<String, String> values) {
        return values.entrySet().stream().map(entry -> encode(entry.getKey()) + "=" + encode(entry.getValue()))
                .collect(Collectors.joining("&"));
    }

    /** The real transport: JDK HTTP client, JSON responses, ten-second timeouts. */
    public static Transport http() {
        HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();
        ObjectMapper json = new ObjectMapper();
        return new Transport() {
            @Override
            public JsonNode get(URI uri, Map<String, String> headers) throws IOException {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10)).header("Accept", "application/json");
                headers.forEach(request::header);
                return send(request.GET().build());
            }

            @Override
            public JsonNode postForm(URI uri, Map<String, String> headers, Map<String, String> form) throws IOException {
                HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(Duration.ofSeconds(10))
                        .header("Accept", "application/json").header("Content-Type", "application/x-www-form-urlencoded");
                headers.forEach(request::header);
                return send(request.POST(HttpRequest.BodyPublishers.ofString(form(form))).build());
            }

            private JsonNode send(HttpRequest request) throws IOException {
                try {
                    HttpResponse<String> response = client.send(request, HttpResponse.BodyHandlers.ofString());
                    if (response.statusCode() / 100 != 2) {
                        throw new IOException("HTTP " + response.statusCode() + " from " + request.uri().getHost());
                    }
                    return json.readTree(response.body());
                } catch (InterruptedException interrupted) {
                    Thread.currentThread().interrupt();
                    throw new IOException("interrupted", interrupted);
                }
            }
        };
    }
}
