package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.NarrativeTestKit;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticReport;
import org.hyzionstudios.mysticquests.narrative.session.PartyExitPolicy;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import java.util.UUID;

import static org.hyzionstudios.mysticquests.narrative.NarrativeTestKit.id;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/** §4.2 account scope through MysticIdentity: state that belongs to the person, across their linked accounts. */
final class AccountScopeTest {
    private static final UUID MAIN = UUID.fromString("00000000-0000-4000-8000-00000000000a");
    private static final UUID ALT = UUID.fromString("00000000-0000-4000-8000-0000000000a2");
    private static final UUID STRANGER = UUID.fromString("00000000-0000-4000-8000-00000000000b");
    private static final String SCHEMA = """
            { "variableSchemas": [ { "id": "grove:legacy", "type": "integer", "scope": "account", "default": 0 } ] }
            """;

    private NarrativeTestKit kit;

    @AfterEach
    void tearDown() {
        if (kit != null) {
            kit.close();
        }
    }

    private int legacy(UUID player) {
        return kit.runtime().variables().get(ScopeContext.player(player), null, id("grove:legacy"))
                .map(value -> ((IntValue) value).value()).orElse(0);
    }

    @Test
    void linkedAccountsShareThePersonsStateAcrossRestarts() {
        kit = new NarrativeTestKit(ScopeSupport.standard(true, true), PartyExitPolicy.FORK);
        kit.accounts.put(MAIN, "identity-1");
        kit.accounts.put(ALT, "identity-1");
        kit.load(SCHEMA);

        StateResult written = kit.runtime().variables().set(ScopeContext.player(MAIN), null, id("grove:legacy"), new IntValue(3));
        assertFalse(written.rejected(), written.message());
        assertEquals(3, legacy(ALT), "the alt account is the same person");

        StateResult stranger = kit.runtime().variables().set(ScopeContext.player(STRANGER), null, id("grove:legacy"), new IntValue(9));
        assertTrue(stranger.rejected());
        assertEquals(DiagnosticCode.SCOPE_UNRESOLVED, stranger.code(), "an unlinked player is skipped, retryably, never redirected");
        assertEquals(3, legacy(MAIN));

        kit.runtime().onQuit(MAIN, java.util.Optional.empty(), false);
        kit.runtime().flush();
        kit.restart();
        assertEquals(3, legacy(ALT), "account state is stored under the identity, not the game account");
    }

    @Test
    void accountScopeIsOnlyAllowedWhenAnIdentityProviderIsInstalled() {
        kit = new NarrativeTestKit(ScopeSupport.standard(true, false), PartyExitPolicy.FORK);
        DiagnosticReport without = kit.compile(SCHEMA);
        assertTrue(without.hasErrors(), "without MysticIdentity, account scope cannot resolve for anyone: " + without.format());
        kit.close();

        kit = new NarrativeTestKit(ScopeSupport.standard(true, true), PartyExitPolicy.FORK);
        DiagnosticReport with = kit.compile(SCHEMA);
        assertFalse(with.hasErrors(), with.format());
    }
}
