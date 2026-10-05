package org.hyzionstudios.mysticquests.studio;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.MutableClock;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;

import java.io.IOException;
import java.net.URI;
import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Base64;
import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §19.2: Studio sign-in through MysticIdentity's OpenID Provider, against a fake provider. */
final class StudioOidcTest {
    private static final String ISSUER = "https://id.example.net";
    private static final UUID PLAYER = UUID.fromString("00000000-0000-4000-8000-0000000000a2");

    private final ObjectMapper json = new ObjectMapper();
    private final MutableClock clock = new MutableClock(Instant.parse("2026-10-05T12:00:00Z"));

    /** A provider that hands out one code and checks the PKCE verifier against the challenge. */
    private final class FakeProvider implements StudioOidc.Transport {
        String challenge;
        String nonce;
        String audience = "mysticquests-studio";
        boolean linked = true;
        Map<String, String> tokenRequest;

        @Override
        public JsonNode get(URI uri, Map<String, String> headers) throws IOException {
            if (uri.getPath().endsWith("openid-configuration")) {
                return json.readTree("""
                        { "issuer": "%s", "authorization_endpoint": "%s/api/v1/sso/authorize",
                          "token_endpoint": "%s/api/v1/sso/token", "userinfo_endpoint": "%s/api/v1/sso/userinfo" }
                        """.formatted(ISSUER, ISSUER, ISSUER, ISSUER));
            }
            assertEquals("Bearer token-1", headers.get("Authorization"));
            return json.readTree(linked
                    ? "{\"sub\":\"1234\",\"mystic\":{\"hytale\":{\"profile_uuid\":\"" + PLAYER + "\",\"profile_username\":\"Lead\"}}}"
                    : "{\"sub\":\"1234\",\"mystic\":{\"hytale\":null}}");
        }

        @Override
        public JsonNode postForm(URI uri, Map<String, String> headers, Map<String, String> form) throws IOException {
            tokenRequest = form;
            if (!StudioOidc.challenge(form.get("code_verifier")).equals(challenge)) {
                throw new IOException("invalid_grant");
            }
            String claims = "{\"iss\":\"" + ISSUER + "\",\"aud\":\"" + audience + "\",\"nonce\":\"" + nonce
                    + "\",\"exp\":" + clock.instant().plusSeconds(300).getEpochSecond() + "}";
            String idToken = "e30." + Base64.getUrlEncoder().withoutPadding().encodeToString(claims.getBytes(StandardCharsets.UTF_8)) + ".sig";
            return json.readTree("{\"access_token\":\"token-1\",\"id_token\":\"" + idToken + "\"}");
        }
    }

    private static Map<String, String> query(URI uri) {
        Map<String, String> values = new HashMap<>();
        for (String pair : uri.getRawQuery().split("&")) {
            String[] parts = pair.split("=", 2);
            values.put(URLDecoder.decode(parts[0], StandardCharsets.UTF_8), URLDecoder.decode(parts[1], StandardCharsets.UTF_8));
        }
        return values;
    }

    private StudioOidc oidc(FakeProvider provider) {
        return new StudioOidc(new StudioOidc.Settings(ISSUER, "mysticquests-studio", "secret", "https://studio.example.net/auth/callback"),
                provider, clock);
    }

    @Test
    void aLinkedAccountSignsInAsItsHytalePlayer() throws StudioException {
        FakeProvider provider = new FakeProvider();
        StudioOidc oidc = oidc(provider);
        Map<String, String> request = query(oidc.begin().authorize());
        assertEquals("openid identity.read hytale.read", request.get("scope"));
        assertEquals("S256", request.get("code_challenge_method"));
        provider.challenge = request.get("code_challenge");
        provider.nonce = request.get("nonce");

        StudioOidc.Identity identity = oidc.complete(request.get("state"), "code-1");
        assertEquals(PLAYER, identity.player());
        assertEquals("Lead", identity.name());
        assertEquals("https://studio.example.net/auth/callback", provider.tokenRequest.get("redirect_uri"));
        assertThrows(StudioException.class, () -> oidc.complete(request.get("state"), "code-1"), "a flow is used once");
    }

    @Test
    void forgedOrStaleFlowsAndUnlinkedAccountsAreRefused() throws StudioException {
        FakeProvider provider = new FakeProvider();
        StudioOidc oidc = oidc(provider);

        assertThrows(StudioException.class, () -> oidc.complete("made-up-state", "code-1"), "an unknown state");

        Map<String, String> wrongAudience = query(oidc.begin().authorize());
        provider.challenge = wrongAudience.get("code_challenge");
        provider.nonce = wrongAudience.get("nonce");
        provider.audience = "someone-else";
        assertThrows(StudioException.class, () -> oidc.complete(wrongAudience.get("state"), "code-1"), "a token for another client");

        provider.audience = "mysticquests-studio";
        Map<String, String> stale = query(oidc.begin().authorize());
        clock.advance(StudioOidc.FLOW_TTL.plusSeconds(1));
        assertThrows(StudioException.class, () -> oidc.complete(stale.get("state"), "code-1"), "an expired flow");

        Map<String, String> unlinked = query(oidc.begin().authorize());
        provider.challenge = unlinked.get("code_challenge");
        provider.nonce = unlinked.get("nonce");
        provider.linked = false;
        StudioException noProfile = assertThrows(StudioException.class, () -> oidc.complete(unlinked.get("state"), "code-1"));
        assertTrue(noProfile.getMessage().contains("no linked Hytale profile"), noProfile.getMessage());
    }
}
