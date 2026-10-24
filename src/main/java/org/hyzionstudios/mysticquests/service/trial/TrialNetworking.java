package org.hyzionstudios.mysticquests.service.trial;

import org.hyzionstudios.mysticquests.api.trial.TrialReceipt;
import org.hyzionstudios.mysticquests.service.trial.network.SqlTrialStore;

import javax.annotation.Nullable;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Executor;
import java.util.function.BiConsumer;
import java.util.function.Predicate;

/**
 * How the trial service reaches the shared trial store (MysticRPG PO addendum R-1), or that it does not.
 *
 * @param store          the opened store; null keeps trials on this server only
 * @param executor       the single thread every store call runs on, in order (never a world thread);
 *                       a direct executor in tests
 * @param playerThread   runs quest-state work for a player where it belongs (their world thread)
 * @param online         whether a player is on this server
 * @param realmId        the realm written on receipts ({@link TrialReceipt#LOCAL_REALM} when none)
 * @param serverId       this server's id, written on receipts and runs
 * @param leaseMillis    how long a claim lasts without renewal
 * @param handoffMillis  how long a handed-off run waits for its player
 * @param peerStaleMillis a member whose heartbeat is older is no transfer target
 * @param problems       why the store is not in use, when {@code store} is null although configured
 */
public record TrialNetworking(
        @Nullable SqlTrialStore store,
        Executor executor,
        BiConsumer<UUID, Runnable> playerThread,
        Predicate<UUID> online,
        String realmId,
        String serverId,
        long leaseMillis,
        long handoffMillis,
        long peerStaleMillis,
        boolean configured,
        List<String> problems) {

    public TrialNetworking {
        realmId = realmId == null || realmId.isBlank() ? TrialReceipt.LOCAL_REALM : realmId;
        serverId = serverId == null ? "" : serverId;
        problems = problems == null ? List.of() : List.copyOf(problems);
        executor = executor == null ? Runnable::run : executor;
        playerThread = playerThread == null ? (player, work) -> work.run() : playerThread;
        online = online == null ? player -> true : online;
    }

    /** Trials kept on this server only. */
    public static TrialNetworking local(String realmId, String serverId, boolean configured, List<String> problems) {
        return new TrialNetworking(null, Runnable::run, null, null, realmId, serverId, 30_000L, 120_000L, 45_000L,
                configured, problems);
    }

    public boolean networked() {
        return store != null;
    }
}
