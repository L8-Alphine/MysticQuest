package org.hyzionstudios.mysticquests.api.trial;

import java.time.Instant;
import java.util.List;
import java.util.Set;

/**
 * Where trial state lives and whether it can follow a player to another server (MysticRPG PO addendum
 * 2026-10-24, R-1). Read by MysticRPG's transfer guard and by its network-release gate. Since trial
 * contract version 2.
 *
 * @param enabled         the shared store is configured ({@code trials.network.enabled})
 * @param networkBacked   the shared store is in use and healthy right now: schema current, last
 *                        heartbeat within the lease. Only then may a player transfer during a trial
 * @param realmId         the realm the store serves; empty when not configured
 * @param serverId        this server's id in the store; empty when not configured
 * @param dialect         {@code h2}, {@code mariadb}, {@code mysql}, {@code postgresql} or {@code local}
 * @param schemaVersion   the store's schema version; 0 when local or not migrated
 * @param peers           servers of the same realm with a fresh heartbeat (this one included)
 * @param activeRuns      runs this server owns right now
 * @param pendingResults  resolved runs not yet durable in the store (retried)
 * @param problems        why the store is not in use or not healthy; empty when healthy
 * @param checkedAt       when this was last observed
 */
public record TrialNetworkStatus(
        boolean enabled,
        boolean networkBacked,
        String realmId,
        String serverId,
        String dialect,
        int schemaVersion,
        Set<String> peers,
        int activeRuns,
        int pendingResults,
        List<String> problems,
        Instant checkedAt) {

    /**
     * The capability a healthy shared trial store reports in {@link #capabilities()}; what a
     * network-release gate looks for.
     */
    public static final String SHARED_SQL_CAPABILITY = "mysticquests:trials/shared-sql";

    /** The schema version this build writes and reads. */
    public static final int SCHEMA_VERSION = 1;

    public TrialNetworkStatus {
        realmId = realmId == null ? "" : realmId;
        serverId = serverId == null ? "" : serverId;
        dialect = dialect == null ? "local" : dialect;
        peers = peers == null ? Set.of() : Set.copyOf(peers);
        problems = problems == null ? List.of() : List.copyOf(problems);
        checkedAt = checkedAt == null ? Instant.EPOCH : checkedAt;
    }

    /** A server keeping trials in {@code data/trials.json}. */
    public static TrialNetworkStatus local(List<String> problems) {
        return new TrialNetworkStatus(false, false, "", "", "local", 0, Set.of(), 0, 0,
                problems == null || problems.isEmpty()
                        ? List.of("trials.network.enabled is false: trial state stays on this server") : problems,
                Instant.now());
    }

    /** {@link #SHARED_SQL_CAPABILITY} while network-backed, nothing otherwise. */
    public Set<String> capabilities() {
        return networkBacked ? Set.of(SHARED_SQL_CAPABILITY) : Set.of();
    }

    /** Whether a server is a live member of this store's realm. */
    public boolean hasPeer(String serverId) {
        return serverId != null && peers.contains(serverId);
    }
}
