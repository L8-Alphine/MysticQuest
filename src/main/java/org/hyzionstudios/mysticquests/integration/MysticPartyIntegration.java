package org.hyzionstudios.mysticquests.integration;

import com.hypixel.hytale.logger.HytaleLogger;
import org.hyzionstudios.mysticquests.api.QuestPartyProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/** Soft integration for MysticRPG party providers and MysticGuilds party lifecycle events. */
public final class MysticPartyIntegration implements QuestPartyProvider, AutoCloseable {
    private static final String GUILDS_API = "org.hyzionstudios.mysticguilds.api.MysticGuildsApi";
    private static final String PARTY_EVENTS =
            "org.hyzionstudios.mysticguilds.application.party.PartyService$PartyEvents$";
    private static final String RPG = "org.hyzionstudios.mysticrpg.api.MysticRPG";

    private final HytaleLogger logger;
    private final Map<UUID, Set<UUID>> partyMembers = new ConcurrentHashMap<>();
    private final Map<UUID, UUID> partyByPlayer = new ConcurrentHashMap<>();
    private final Set<AutoCloseable> subscriptions = ConcurrentHashMap.newKeySet();
    private final List<LifecycleListener> lifecycleListeners = new CopyOnWriteArrayList<>();
    private volatile QuestPartyProvider rpgProvider;

    /** Party membership changes that party-owned story sessions must react to. */
    public interface LifecycleListener {
        void memberLeft(String partyId, UUID playerId);

        void disbanded(String partyId, Set<UUID> members);
    }

    public MysticPartyIntegration(HytaleLogger logger) {
        this.logger = logger;
    }

    public void register() {
        resolveMysticRpgProvider();
        subscribeMysticGuilds();
    }

    @Override
    public Collection<UUID> members(UUID actorId) {
        QuestPartyProvider external = rpgProvider;
        if (external != null && external != this) {
            try {
                Collection<UUID> members = external.members(actorId);
                if (members != null && !members.isEmpty()) {
                    return Set.copyOf(members);
                }
            } catch (RuntimeException exception) {
                logger.at(Level.WARNING).withCause(exception).log("MysticRPG party provider failed.");
            }
        }
        UUID partyId = partyByPlayer.get(actorId);
        if (partyId == null) {
            return Set.of(actorId);
        }
        Set<UUID> members = partyMembers.get(partyId);
        return members == null || members.isEmpty() ? Set.of(actorId) : Set.copyOf(members);
    }

    /**
     * The stable id of the player's party, which party-owned story sessions are keyed by. Only
     * MysticGuilds supplies one: a MysticRPG provider reports members but no id, so on such a server
     * party story sessions fall back to each player's own session.
     */
    public Optional<String> partyId(UUID playerId) {
        QuestPartyProvider external = rpgProvider;
        if (external != null && external != this) {
            try {
                Optional<String> id = external.partyId(playerId);
                if (id != null && id.isPresent() && !id.get().isBlank()) {
                    return id;
                }
            } catch (RuntimeException exception) {
                logger.at(Level.WARNING).withCause(exception).log("MysticRPG party provider failed to report a party id.");
            }
        }
        UUID partyId = partyByPlayer.get(playerId);
        return partyId == null ? Optional.empty() : Optional.of(partyId.toString());
    }

    /** Whether any party provider was found, so party scope can be reported as usable or not. */
    public boolean available() {
        return rpgProvider != null || !subscriptions.isEmpty();
    }

    /**
     * Whether party story sessions can be keyed: MysticGuilds' lifecycle events carry a party id, and
     * a MysticRPG provider can supply one through {@link QuestPartyProvider#partyId}. A MysticRPG
     * provider is assumed to when it overrides that method.
     */
    public boolean supportsPartyIds() {
        QuestPartyProvider external = rpgProvider;
        return !subscriptions.isEmpty() || (external != null && overridesPartyId(external));
    }

    private static boolean overridesPartyId(QuestPartyProvider provider) {
        try {
            return provider.getClass().getMethod("partyId", UUID.class).getDeclaringClass() != QuestPartyProvider.class;
        } catch (NoSuchMethodException impossible) {
            return false;
        }
    }

    /** Which providers are connected, for {@code /mq integrations}. */
    public String providerName() {
        if (rpgProvider != null && !subscriptions.isEmpty()) {
            return "MysticRPG + MysticGuilds";
        }
        return rpgProvider != null ? "MysticRPG" : !subscriptions.isEmpty() ? "MysticGuilds" : "none";
    }

    public void addLifecycleListener(LifecycleListener listener) {
        lifecycleListeners.add(listener);
    }

    private void resolveMysticRpgProvider() {
        try {
            Class<?> entrypoint = Class.forName(RPG);
            Optional<?> api = (Optional<?>) entrypoint.getMethod("api").invoke(null);
            if (api.isEmpty()) {
                return;
            }
            Object services = api.get().getClass().getMethod("services").invoke(api.get());
            Method find = services.getClass().getMethod("find", Class.class);
            find.setAccessible(true);
            Optional<?> provider = (Optional<?>) find.invoke(services, QuestPartyProvider.class);
            if (provider.isPresent() && provider.get() instanceof QuestPartyProvider compatible) {
                rpgProvider = compatible;
                logger.at(Level.INFO).log("MysticQuests party sharing is using the MysticRPG service registry.");
            }
        } catch (ClassNotFoundException ignored) {
            // Soft dependency is absent.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("MysticRPG party capability is unavailable.");
        }
    }

    private void subscribeMysticGuilds() {
        try {
            Class<?> apiClass = Class.forName(GUILDS_API);
            Optional<?> api = (Optional<?>) apiClass.getMethod("get").invoke(null);
            if (api.isEmpty()) {
                return;
            }
            Object eventBus = apiClass.getMethod("events").invoke(api.get());
            subscribe(eventBus, "PartyCreated");
            subscribe(eventBus, "MemberJoined");
            subscribe(eventBus, "MemberLeft");
            subscribe(eventBus, "PartyDisbanded");
            logger.at(Level.INFO).log("MysticQuests is tracking MysticGuilds parties for shared objectives.");
        } catch (ClassNotFoundException ignored) {
            // Soft dependency is absent.
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Could not connect MysticGuilds party events.");
        }
    }

    @SuppressWarnings({"unchecked", "rawtypes"})
    private void subscribe(Object eventBus, String eventName) throws ReflectiveOperationException {
        Class<?> eventType = Class.forName(PARTY_EVENTS + eventName);
        Method subscribe = eventBus.getClass().getMethod("subscribe", Class.class, Consumer.class);
        subscribe.setAccessible(true);
        Object handle = subscribe.invoke(eventBus, eventType, (Consumer) this::onGuildEvent);
        if (handle instanceof AutoCloseable closeable) {
            subscriptions.add(closeable);
        }
    }

    private void onGuildEvent(Object event) {
        try {
            String name = event.getClass().getSimpleName();
            UUID partyId = uuid(event, "partyId");
            switch (name) {
                case "PartyCreated" -> add(partyId, uuid(event, "leaderId"));
                case "MemberJoined" -> add(partyId, uuid(event, "playerId"));
                case "MemberLeft" -> remove(partyId, uuid(event, "playerId"));
                case "PartyDisbanded" -> disband(partyId);
                default -> { }
            }
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Could not apply MysticGuilds party event.");
        }
    }

    private static UUID uuid(Object event, String name) throws ReflectiveOperationException {
        Field field = event.getClass().getField(name);
        return (UUID) field.get(event);
    }

    private void add(UUID partyId, UUID playerId) {
        if (partyId == null || playerId == null) return;
        partyMembers.computeIfAbsent(partyId, ignored -> ConcurrentHashMap.newKeySet()).add(playerId);
        partyByPlayer.put(playerId, partyId);
    }

    private void remove(UUID partyId, UUID playerId) {
        partyByPlayer.remove(playerId, partyId);
        Set<UUID> members = partyMembers.get(partyId);
        if (members != null) {
            members.remove(playerId);
            if (members.isEmpty()) partyMembers.remove(partyId, members);
        }
        if (partyId != null && playerId != null) {
            notify(listener -> listener.memberLeft(partyId.toString(), playerId));
        }
    }

    private void disband(UUID partyId) {
        Set<UUID> members = partyMembers.remove(partyId);
        if (members != null) members.forEach(playerId -> partyByPlayer.remove(playerId, partyId));
        if (partyId != null) {
            Set<UUID> former = members == null ? Set.of() : Set.copyOf(members);
            notify(listener -> listener.disbanded(partyId.toString(), former));
        }
    }

    private void notify(Consumer<LifecycleListener> event) {
        for (LifecycleListener listener : lifecycleListeners) {
            try {
                event.accept(listener);
            } catch (RuntimeException exception) {
                logger.at(Level.WARNING).withCause(exception).log("A party lifecycle listener failed.");
            }
        }
    }

    @Override
    public void close() {
        for (AutoCloseable subscription : new LinkedHashSet<>(subscriptions)) {
            try {
                subscription.close();
            } catch (Exception exception) {
                logger.at(Level.FINE).withCause(exception).log("Failed to close a party event subscription.");
            }
        }
        subscriptions.clear();
        lifecycleListeners.clear();
        partyMembers.clear();
        partyByPlayer.clear();
    }
}
