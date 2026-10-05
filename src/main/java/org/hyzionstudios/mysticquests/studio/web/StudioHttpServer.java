package org.hyzionstudios.mysticquests.studio.web;

import org.hyzionstudios.mysticquests.studio.StudioAudio;
import org.hyzionstudios.mysticquests.studio.ContentRoot;
import org.hyzionstudios.mysticquests.studio.StudioAuth.Session;
import org.hyzionstudios.mysticquests.studio.StudioException;
import org.hyzionstudios.mysticquests.studio.StudioOidc;
import org.hyzionstudios.mysticquests.studio.StudioService;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;
import com.sun.net.httpserver.Headers;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import javax.annotation.Nullable;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.function.Consumer;
import java.util.regex.Pattern;

/**
 * The Studio's web server (§11, §23): the JSON API under {@code /api/} and the Studio page itself,
 * on the JDK's built-in HTTP server so the plugin needs no web framework.
 *
 * <p>Security, in the order a request meets it:
 * <ul>
 *   <li>Bodies are limited in size; static files come only from a fixed list of names.</li>
 *   <li>The session is an {@code HttpOnly}, {@code SameSite=Strict} cookie ({@code Secure} behind
 *       HTTPS). Every write also needs the session's CSRF token in {@code X-CSRF-Token}, and a
 *       browser {@code Origin}, when sent, must be this server.</li>
 *   <li>{@link StudioService} authorizes each call against the player's current permissions.</li>
 *   <li>Responses carry a strict Content-Security-Policy and are never framed or cached.</li>
 * </ul>
 *
 * <p>It binds to {@code 127.0.0.1} by default. Expose it to other machines only behind a reverse
 * proxy with HTTPS, and set {@code publicUrl} so links and the origin check use that address.
 */
public final class StudioHttpServer implements AutoCloseable {
    static final String COOKIE = "mq_studio";
    /** Binds a MysticIdentity sign-in to the browser that started it (login CSRF). */
    static final String STATE_COOKIE = "mq_studio_signin";
    /** API routes that work before signing in. */
    private static final java.util.Set<String> PUBLIC_ROUTES = java.util.Set.of("/api/login", "/api/sign-in-options");
    static final int MAX_BODY = 2 * 1024 * 1024;
    private static final Pattern ASSET = Pattern.compile("[a-z0-9_-]{1,40}\\.(html|js|css|svg)");
    private static final String CSP = "default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; "
            + "media-src 'self'; connect-src 'self'; font-src 'self'; frame-ancestors 'none'; base-uri 'none'; form-action 'self'";
    /** Routes whose request body is a file, not JSON, with the largest file each accepts. */
    private static final Map<String, Integer> RAW_ROUTES = Map.of(
            "/api/audio", (int) StudioAudio.MAX_BYTES,
            "/api/releases/import", 16 * 1024 * 1024);

    /**
     * A route result sent as-is instead of as JSON, such as a sound for the preview player.
     *
     * @param filename offered to the browser as a download, or null to show inline
     */
    record Binary(byte[] bytes, String contentType, @Nullable String filename) {
    }

    /** One API route: the signed-in session (null for sign-in) and the parsed request. */
    @FunctionalInterface
    interface Route {
        Object handle(@Nullable Session session, Request request) throws StudioException, IOException;
    }

    /** A parsed request: method, query parameters and, for writes, the JSON body. */
    record Request(String method, Map<String, String> query, JsonNode body, byte[] raw, String address) {
        String param(String name) {
            String value = query.get(name);
            return value == null ? body.path(name).asText(null) : value;
        }

        int intParam(String name) throws StudioException {
            String value = param(name);
            try {
                return Integer.parseInt(value);
            } catch (NumberFormatException | NullPointerException invalid) {
                throw new StudioException(StudioException.Status.BAD_REQUEST, "'" + name + "' must be a number.");
            }
        }
    }

    private final StudioService studio;
    private final HttpServer server;
    private final ExecutorService workers;
    private final ObjectMapper json;
    private final String publicOrigin;
    private final boolean secureCookies;
    private final Consumer<String> log;
    private final Map<String, Map<String, Route>> routes = new LinkedHashMap<>();

    /**
     * @param publicUrl the address creators open, such as {@code https://studio.example.net/}; blank
     *         for {@code http://<bind>:<port>/}
     */
    public StudioHttpServer(StudioService studio, String bind, int port, String publicUrl, Consumer<String> log) throws IOException {
        this.studio = studio;
        this.log = log;
        this.json = new ObjectMapper().registerModule(new JavaTimeModule())
                .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);
        this.server = HttpServer.create(new InetSocketAddress(bind, port), 32);
        // The bound port, so port 0 (any free port, used by tests) still gives a working address.
        String url = publicUrl == null || publicUrl.isBlank()
                ? "http://" + bind + ":" + server.getAddress().getPort() + "/" : publicUrl;
        URI uri = URI.create(url);
        this.publicOrigin = uri.getScheme() + "://" + uri.getAuthority();
        this.secureCookies = "https".equalsIgnoreCase(uri.getScheme());
        this.workers = Executors.newFixedThreadPool(4, runnable -> {
            Thread thread = new Thread(runnable, "MysticQuests-Studio");
            thread.setDaemon(true);
            return thread;
        });
        server.setExecutor(workers);
        StudioApi.register(this, studio);
        server.createContext("/api/", this::api);
        server.createContext("/auth/", this::identity);
        server.createContext("/", this::asset);
    }

    /** The address to open, with a trailing slash. */
    public String publicUrl() {
        return publicOrigin + "/";
    }

    public void start() {
        server.start();
    }

    @Override
    public void close() {
        server.stop(1);
        workers.shutdown();
        try {
            workers.awaitTermination(3, TimeUnit.SECONDS);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
        }
    }

    void route(String method, String path, Route route) {
        routes.computeIfAbsent(path, ignored -> new LinkedHashMap<>()).put(method, route);
    }

    ObjectMapper json() {
        return json;
    }

    // --- API ---

    private void api(HttpExchange exchange) throws IOException {
        try (exchange) {
            securityHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String path = exchange.getRequestURI().getPath();
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            try {
                Map<String, Route> byMethod = routes.get(path);
                if (byMethod == null) {
                    throw new StudioException(StudioException.Status.NOT_FOUND, "No such Studio endpoint.");
                }
                Route route = byMethod.get(method);
                if (route == null) {
                    throw new StudioException(StudioException.Status.BAD_REQUEST, method + " is not supported here.");
                }
                boolean write = !method.equals("GET");
                if (write) {
                    checkOrigin(exchange);
                }
                boolean raw = write && RAW_ROUTES.containsKey(path) && method.equals("PUT");
                Request request = new Request(method, query(exchange.getRequestURI()),
                        write && !raw ? body(exchange) : json.createObjectNode(),
                        raw ? bytes(exchange, RAW_ROUTES.get(path)) : new byte[0], address(exchange));
                Session session = null;
                if (!PUBLIC_ROUTES.contains(path)) {
                    session = session(exchange);
                    if (write && !constantTimeEquals(session.csrf(), exchange.getRequestHeaders().getFirst("X-CSRF-Token"))) {
                        throw new StudioException(StudioException.Status.FORBIDDEN, "Missing or wrong CSRF token; reload the Studio.");
                    }
                }
                Object result = route.handle(session, request);
                if (result instanceof Binary binary) {
                    exchange.getResponseHeaders().set("Content-Type", binary.contentType());
                    if (binary.filename() != null) {
                        exchange.getResponseHeaders().set("Content-Disposition", "attachment; filename=\"" + binary.filename() + "\"");
                    }
                    exchange.sendResponseHeaders(200, binary.bytes().length);
                    exchange.getResponseBody().write(binary.bytes());
                    return;
                }
                if (result instanceof Session signedIn) {
                    exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + signedIn.token() + "; Path=/; HttpOnly; SameSite=Strict"
                            + (secureCookies ? "; Secure" : ""));
                    result = studio.me(signedIn);
                } else if (path.equals("/api/logout")) {
                    exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=; Path=/; Max-Age=0; HttpOnly; SameSite=Strict"
                            + (secureCookies ? "; Secure" : ""));
                }
                send(exchange, 200, result == null ? json.createObjectNode().put("ok", true) : result);
            } catch (StudioException refused) {
                ObjectNode error = json.createObjectNode().put("error", refused.getMessage());
                if (refused.detail() != null) {
                    error.set("detail", json.valueToTree(refused.detail()));
                }
                send(exchange, refused.status().http(), error);
            } catch (RuntimeException | IOException failure) {
                log.accept("Studio request " + method + " " + path + " failed: " + failure);
                send(exchange, 500, json.createObjectNode().put("error", "The server could not complete that; the server log has details."));
            }
        }
    }

    private Session session(HttpExchange exchange) throws StudioException {
        String token = cookie(exchange.getRequestHeaders(), COOKIE);
        return studio.auth().session(token).orElseThrow(() ->
                new StudioException(StudioException.Status.UNAUTHORIZED, "Sign in with a code from /mquest studio login."));
    }

    /** A browser always sends Origin on cross-site writes; when present it must be this server. */
    private void checkOrigin(HttpExchange exchange) throws StudioException {
        String origin = exchange.getRequestHeaders().getFirst("Origin");
        if (origin == null) {
            return;
        }
        String host = exchange.getRequestHeaders().getFirst("Host");
        boolean sameHost = host != null && (origin.equals("http://" + host) || origin.equals("https://" + host));
        if (!origin.equals(publicOrigin) && !sameHost) {
            throw new StudioException(StudioException.Status.FORBIDDEN, "Requests from " + origin + " are not accepted.");
        }
    }

    private JsonNode body(HttpExchange exchange) throws IOException, StudioException {
        byte[] bytes = bytes(exchange, MAX_BODY);
        if (bytes.length == 0) {
            return json.createObjectNode();
        }
        try {
            JsonNode parsed = json.readTree(bytes);
            return parsed == null ? json.createObjectNode() : parsed;
        } catch (IOException invalid) {
            throw new StudioException(StudioException.Status.BAD_REQUEST, "The request body is not valid JSON.");
        }
    }

    private static byte[] bytes(HttpExchange exchange, int limit) throws IOException, StudioException {
        try (InputStream in = exchange.getRequestBody()) {
            ByteArrayOutputStream buffer = new ByteArrayOutputStream();
            byte[] chunk = new byte[8192];
            int read;
            while ((read = in.read(chunk)) != -1) {
                buffer.write(chunk, 0, read);
                if (buffer.size() > limit) {
                    throw new StudioException(StudioException.Status.TOO_LARGE, "The request is too large.");
                }
            }
            return buffer.toByteArray();
        }
    }

    private void send(HttpExchange exchange, int status, Object body) throws IOException {
        byte[] bytes = json.writeValueAsBytes(body);
        exchange.getResponseHeaders().set("Content-Type", "application/json; charset=utf-8");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }

    // --- MysticIdentity sign-in ---

    /**
     * {@code /auth/login} sends the browser to MysticIdentity; {@code /auth/callback} receives it back,
     * opens the session and returns to the Studio. A failure goes back to the sign-in page with the
     * reason in the fragment, which never reaches a server.
     */
    private void identity(HttpExchange exchange) throws IOException {
        try (exchange) {
            securityHeaders(exchange.getResponseHeaders());
            exchange.getResponseHeaders().set("Cache-Control", "no-store");
            String path = exchange.getRequestURI().getPath();
            try {
                if (path.equals("/auth/login")) {
                    StudioOidc.Start start = studio.beginIdentitySignIn();
                    // Lax, not Strict: the callback is a top-level redirect from MysticIdentity's site.
                    exchange.getResponseHeaders().add("Set-Cookie", STATE_COOKIE + "=" + start.state()
                            + "; Path=/auth; Max-Age=600; HttpOnly; SameSite=Lax" + (secureCookies ? "; Secure" : ""));
                    redirect(exchange, start.authorize().toString());
                } else if (path.equals("/auth/callback")) {
                    Map<String, String> query = query(exchange.getRequestURI());
                    if (query.containsKey("error")) {
                        throw new StudioException(StudioException.Status.UNAUTHORIZED, "MysticIdentity: " + query.get("error"));
                    }
                    String started = cookie(exchange.getRequestHeaders(), STATE_COOKIE);
                    exchange.getResponseHeaders().add("Set-Cookie", STATE_COOKIE + "=; Path=/auth; Max-Age=0; HttpOnly; SameSite=Lax"
                            + (secureCookies ? "; Secure" : ""));
                    if (started == null || !constantTimeEquals(started, query.get("state"))) {
                        throw new StudioException(StudioException.Status.UNAUTHORIZED,
                                "This sign-in was not started in this browser. Start again from the Studio.");
                    }
                    Session session = studio.completeIdentitySignIn(query.get("state"), query.get("code"), address(exchange));
                    exchange.getResponseHeaders().add("Set-Cookie", COOKIE + "=" + session.token() + "; Path=/; HttpOnly; SameSite=Strict"
                            + (secureCookies ? "; Secure" : ""));
                    redirect(exchange, "/");
                } else {
                    redirect(exchange, "/");
                }
            } catch (StudioException refused) {
                redirect(exchange, "/#signin-error=" + java.net.URLEncoder.encode(refused.getMessage(), StandardCharsets.UTF_8));
            }
        }
    }

    private static void redirect(HttpExchange exchange, String location) throws IOException {
        exchange.getResponseHeaders().set("Location", location);
        exchange.sendResponseHeaders(302, -1);
    }

    // --- The Studio page ---

    private void asset(HttpExchange exchange) throws IOException {
        try (exchange) {
            securityHeaders(exchange.getResponseHeaders());
            String path = exchange.getRequestURI().getPath();
            String name = path.equals("/") ? "index.html" : path.substring(1);
            String method = exchange.getRequestMethod().toUpperCase(Locale.ROOT);
            boolean head = method.equals("HEAD");
            byte[] bytes = null;
            if (ASSET.matcher(name).matches() && (head || method.equals("GET"))) {
                try (InputStream in = StudioHttpServer.class.getResourceAsStream("/studio-web/" + name)) {
                    bytes = in == null ? null : in.readAllBytes();
                }
            }
            if (bytes == null) {
                byte[] missing = "Not found".getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "text/plain; charset=utf-8");
                exchange.sendResponseHeaders(404, head ? -1 : missing.length);
                if (!head) {
                    exchange.getResponseBody().write(missing);
                }
                return;
            }
            // A content ETag, so a browser revalidates and never runs a page from an older plugin.
            String etag = "\"" + ContentRoot.hash(bytes) + "\"";
            exchange.getResponseHeaders().set("ETag", etag);
            exchange.getResponseHeaders().set("Cache-Control", "no-cache");
            if (etag.equals(exchange.getRequestHeaders().getFirst("If-None-Match"))) {
                exchange.sendResponseHeaders(304, -1);
                return;
            }
            exchange.getResponseHeaders().set("Content-Type", contentType(name));
            exchange.sendResponseHeaders(200, head ? -1 : bytes.length);
            if (!head) {
                exchange.getResponseBody().write(bytes);
            }
        }
    }

    private static String contentType(String name) {
        String extension = name.substring(name.lastIndexOf('.') + 1);
        return switch (extension) {
            case "html" -> "text/html; charset=utf-8";
            case "js" -> "text/javascript; charset=utf-8";
            case "css" -> "text/css; charset=utf-8";
            default -> "image/svg+xml";
        };
    }

    private static void securityHeaders(Headers headers) {
        headers.set("X-Content-Type-Options", "nosniff");
        headers.set("X-Frame-Options", "DENY");
        headers.set("Referrer-Policy", "no-referrer");
        headers.set("Content-Security-Policy", CSP);
    }

    // --- Helpers ---

    static Map<String, String> query(URI uri) {
        Map<String, String> query = new LinkedHashMap<>();
        String raw = uri.getRawQuery();
        if (raw == null || raw.isEmpty()) {
            return query;
        }
        for (String pair : raw.split("&")) {
            int equals = pair.indexOf('=');
            String key = URLDecoder.decode(equals < 0 ? pair : pair.substring(0, equals), StandardCharsets.UTF_8);
            String value = equals < 0 ? "" : URLDecoder.decode(pair.substring(equals + 1), StandardCharsets.UTF_8);
            query.putIfAbsent(key, value);
        }
        return query;
    }

    @Nullable
    static String cookie(Headers headers, String name) {
        for (String header : headers.getOrDefault("Cookie", java.util.List.of())) {
            for (String part : header.split(";")) {
                String trimmed = part.strip();
                if (trimmed.startsWith(name + "=")) {
                    return trimmed.substring(name.length() + 1);
                }
            }
        }
        return null;
    }

    private static String address(HttpExchange exchange) {
        InetSocketAddress remote = exchange.getRemoteAddress();
        return remote == null || remote.getAddress() == null ? "unknown" : remote.getAddress().getHostAddress();
    }

    private static boolean constantTimeEquals(String expected, @Nullable String given) {
        return given != null && MessageDigest.isEqual(expected.getBytes(StandardCharsets.UTF_8), given.getBytes(StandardCharsets.UTF_8));
    }
}
