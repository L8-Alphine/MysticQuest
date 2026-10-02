package org.hyzionstudios.mysticquests.narrative.state;

import java.util.EnumMap;
import java.util.Map;
import java.util.Objects;

/**
 * Which scopes this deployment can actually honour, and why the others cannot be honoured.
 *
 * <p>The specification lets support vary by deployment but forbids silent degradation. So every
 * scope is in exactly one state, and content validation reports anything not
 * {@link Level#SUPPORTED} with the stored reason.
 */
public final class ScopeSupport {
    public enum Level {
        SUPPORTED,
        /** Usable, but it will not resolve in every situation; validation warns. */
        DEGRADED,
        /** Cannot resolve at all in this deployment; validation fails. */
        UNSUPPORTED
    }

    public record Status(Level level, String reason) {
    }

    private final Map<VariableScope, Status> statuses = new EnumMap<>(VariableScope.class);

    private ScopeSupport() {
        for (VariableScope scope : VariableScope.values()) {
            statuses.put(scope, new Status(Level.SUPPORTED, ""));
        }
    }

    /**
     * The scopes a standalone server supports. Account, network and season state need a provider
     * that this mod does not ship, so they start unsupported until one is bound.
     *
     * @param partyProvider whether a party provider is installed. Without one, party scope cannot
     *         resolve for anyone, so it is degraded rather than refused, and the content stays loadable.
     */
    public static ScopeSupport standard(boolean partyProvider) {
        ScopeSupport support = new ScopeSupport();
        support.set(VariableScope.ACCOUNT, Level.UNSUPPORTED,
                "no identity provider is bound (install and enable MysticIdentity)");
        support.set(VariableScope.NETWORK, Level.UNSUPPORTED, "no network state provider is configured");
        support.set(VariableScope.SEASON, Level.UNSUPPORTED, "no season provider is configured");
        if (!partyProvider) {
            support.set(VariableScope.PARTY, Level.DEGRADED,
                    "no party provider is installed; party-scoped writes will be skipped");
        }
        return support;
    }

    /** Every scope supported; for tests and for deployments that bind every provider. */
    public static ScopeSupport all() {
        return new ScopeSupport();
    }

    public ScopeSupport set(VariableScope scope, Level level, String reason) {
        statuses.put(Objects.requireNonNull(scope, "scope"), new Status(level, reason == null ? "" : reason));
        return this;
    }

    public Status status(VariableScope scope) {
        return statuses.get(scope);
    }

    public boolean usable(VariableScope scope) {
        return statuses.get(scope).level() != Level.UNSUPPORTED;
    }
}
