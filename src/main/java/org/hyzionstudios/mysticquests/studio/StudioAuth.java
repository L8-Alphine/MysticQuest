package org.hyzionstudios.mysticquests.studio;

import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.Base64;
import java.util.Deque;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Studio sign-in (§19.2, §23) without an external identity provider: a player with
 * {@code mysticquests.studio.login} asks for a one-time code in game, and the code, redeemed once
 * within a few minutes, opens a browser session bound to that player. What the session may do is
 * decided per request from the player's current permissions, so revoking a permission takes effect
 * on the next request, not at the next login.
 *
 * <p>Codes are single-use, short-lived and one per player; failed redemptions are limited per
 * address. Session tokens are 256 random bits, expire after an idle period and an absolute
 * lifetime, and come with a separate CSRF token that every write must echo in a header.
 */
public final class StudioAuth {
    static final Duration CODE_TTL = Duration.ofMinutes(5);
    static final Duration IDLE_TIMEOUT = Duration.ofHours(2);
    static final Duration MAX_AGE = Duration.ofHours(12);
    static final int MAX_FAILURES = 10;
    static final Duration FAILURE_WINDOW = Duration.ofMinutes(10);
    private static final String CODE_ALPHABET = "ABCDEFGHJKLMNPQRSTUVWXYZ23456789";
    private static final int CODE_LENGTH = 10;

    /** A signed-in Studio user. */
    public static final class Session {
        private final String token;
        private final String csrf;
        private final UUID player;
        private final String name;
        private final Instant created;
        private volatile Instant lastSeen;

        Session(String token, String csrf, UUID player, String name, Instant created) {
            this.token = token;
            this.csrf = csrf;
            this.player = player;
            this.name = name;
            this.created = created;
            this.lastSeen = created;
        }

        public String token() {
            return token;
        }

        public String csrf() {
            return csrf;
        }

        public UUID player() {
            return player;
        }

        public String name() {
            return name;
        }

        public Instant created() {
            return created;
        }

        /** How the audit trail names this user. */
        public String actor() {
            return name + " (" + player + ")";
        }
    }

    private record Pending(UUID player, String name, Instant expires) {
    }

    private final Clock clock;
    private final SecureRandom random = new SecureRandom();
    private final Map<String, Pending> codes = new ConcurrentHashMap<>();
    private final Map<String, Session> sessions = new ConcurrentHashMap<>();
    private final Map<String, Deque<Instant>> failures = new ConcurrentHashMap<>();

    public StudioAuth(Clock clock) {
        this.clock = clock;
    }

    /** A fresh one-time code for {@code player}; any earlier unredeemed code of theirs stops working. */
    public String issueCode(UUID player, String name) {
        codes.values().removeIf(pending -> pending.player().equals(player) || expired(pending));
        StringBuilder code = new StringBuilder(CODE_LENGTH);
        for (int index = 0; index < CODE_LENGTH; index++) {
            code.append(CODE_ALPHABET.charAt(random.nextInt(CODE_ALPHABET.length())));
        }
        codes.put(code.toString(), new Pending(player, name, clock.instant().plus(CODE_TTL)));
        return code.toString();
    }

    /**
     * Exchanges a code for a session.
     *
     * @param address the client's address, for limiting guesses
     * @throws StudioException {@code TOO_MANY_REQUESTS} after repeated failures from one address, or
     *         {@code UNAUTHORIZED} for an unknown, used or expired code
     */
    public Session redeem(String code, String address) throws StudioException {
        Deque<Instant> recent = failures.computeIfAbsent(address, ignored -> new ArrayDeque<>());
        synchronized (recent) {
            Instant cutoff = clock.instant().minus(FAILURE_WINDOW);
            while (!recent.isEmpty() && recent.peekFirst().isBefore(cutoff)) {
                recent.pollFirst();
            }
            if (recent.size() >= MAX_FAILURES) {
                throw new StudioException(StudioException.Status.TOO_MANY_REQUESTS,
                        "Too many sign-in attempts. Wait a few minutes and ask for a new code in game.");
            }
            String normalized = code == null ? "" : code.strip().toUpperCase(Locale.ROOT).replace("-", "");
            Pending pending = normalized.isEmpty() ? null : codes.remove(normalized);
            if (pending == null || expired(pending)) {
                recent.addLast(clock.instant());
                throw new StudioException(StudioException.Status.UNAUTHORIZED,
                        "That code is wrong, used or expired. Run /mquest studio login in game for a new one.");
            }
            Session session = new Session(token(), token(), pending.player(), pending.name(), clock.instant());
            sessions.put(session.token(), session);
            return session;
        }
    }

    /**
     * Opens a session for a player whose identity was verified by another sign-in, such as
     * MysticIdentity. Callers check the login permission, as {@link StudioService} does.
     */
    public Session open(UUID player, String name) {
        Session session = new Session(token(), token(), player, name, clock.instant());
        sessions.put(session.token(), session);
        return session;
    }

    /** The live session for a token, refreshing its idle timer; empty when unknown or expired. */
    public Optional<Session> session(String token) {
        if (token == null || token.isEmpty()) {
            return Optional.empty();
        }
        Session session = sessions.get(token);
        if (session == null) {
            return Optional.empty();
        }
        Instant now = clock.instant();
        if (now.isAfter(session.lastSeen.plus(IDLE_TIMEOUT)) || now.isAfter(session.created.plus(MAX_AGE))) {
            sessions.remove(token);
            return Optional.empty();
        }
        session.lastSeen = now;
        return Optional.of(session);
    }

    public void logout(String token) {
        if (token != null) {
            sessions.remove(token);
        }
    }

    /** Signs a player out everywhere, for example when staff remove their Studio access. */
    public int revoke(UUID player) {
        int before = sessions.size();
        sessions.values().removeIf(session -> session.player().equals(player));
        codes.values().removeIf(pending -> pending.player().equals(player));
        return before - sessions.size();
    }

    public int activeSessions() {
        Instant now = clock.instant();
        sessions.values().removeIf(session -> now.isAfter(session.lastSeen.plus(IDLE_TIMEOUT))
                || now.isAfter(session.created.plus(MAX_AGE)));
        return sessions.size();
    }

    private boolean expired(Pending pending) {
        return clock.instant().isAfter(pending.expires());
    }

    private String token() {
        byte[] bytes = new byte[32];
        random.nextBytes(bytes);
        return Base64.getUrlEncoder().withoutPadding().encodeToString(bytes);
    }
}
