package org.hyzionstudios.mysticquests.integration;

import com.hypixel.hytale.logger.HytaleLogger;
import org.hyzionstudios.mysticquests.api.QuestPartyProvider;

import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
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
    private volatile QuestPartyProvider rpgProvider;

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
    }

    private void disband(UUID partyId) {
        Set<UUID> members = partyMembers.remove(partyId);
        if (members != null) members.forEach(playerId -> partyByPlayer.remove(playerId, partyId));
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
        partyMembers.clear();
        partyByPlayer.clear();
    }
}
