package org.hyzionstudios.mysticquests.integration;

import org.hyzionstudios.mysticquests.service.ConversationService;

import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.server.core.universe.PlayerRef;

import org.joml.Vector3d;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Method;
import java.lang.reflect.Proxy;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.logging.Level;

public final class HyCitizensBridge implements AutoCloseable {
    private static final String PLUGIN_CLASS = "com.electro.hycitizens.HyCitizensPlugin";
    private static final String LISTENER_CLASS = "com.electro.hycitizens.events.CitizenInteractListener";

    private final boolean enabled;
    private final ConversationService conversationService;
    private final HytaleLogger logger;
    private Object citizensManager;
    private Object interactListener;
    private Method removeInteractListenerMethod;
    private boolean available;

    public HyCitizensBridge(boolean enabled, ConversationService conversationService, HytaleLogger logger) {
        this.enabled = enabled;
        this.conversationService = Objects.requireNonNull(conversationService, "conversationService");
        this.logger = Objects.requireNonNull(logger, "logger");
    }

    public void register() {
        if (!enabled) {
            return;
        }
        try {
            Class<?> pluginClass = Class.forName(PLUGIN_CLASS);
            Object plugin = pluginClass.getMethod("get").invoke(null);
            if (plugin == null) {
                logger.at(Level.FINE).log("HyCitizens plugin class is present but no active plugin instance was available.");
                return;
            }
            citizensManager = pluginClass.getMethod("getCitizensManager").invoke(plugin);
            if (citizensManager == null) {
                logger.at(Level.FINE).log("HyCitizens plugin is present but its CitizensManager was unavailable.");
                return;
            }
            Class<?> listenerClass = Class.forName(LISTENER_CLASS);
            interactListener = Proxy.newProxyInstance(
                    listenerClass.getClassLoader(),
                    new Class<?>[] { listenerClass },
                    new CitizenInteractInvocationHandler());
            citizensManager.getClass().getMethod("addCitizenInteractListener", listenerClass).invoke(citizensManager, interactListener);
            removeInteractListenerMethod = citizensManager.getClass().getMethod("removeCitizenInteractListener", listenerClass);
            available = true;
            logger.at(Level.INFO).log("HyCitizens bridge enabled for MysticQuests conversations.");
        } catch (ClassNotFoundException exception) {
            logger.at(Level.FINE).log("HyCitizens is not installed; MysticQuests will use native entity conversations.");
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.WARNING).withCause(exception).log("Failed to enable the optional HyCitizens bridge.");
        }
    }

    public boolean enabled() {
        return enabled;
    }

    public boolean available() {
        return available;
    }

    public List<CitizenView> citizens() {
        if (!available || citizensManager == null) {
            return List.of();
        }
        try {
            Object result = citizensManager.getClass().getMethod("getAllCitizens").invoke(citizensManager);
            return toCitizenViews(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Failed to list HyCitizens citizens.");
            return List.of();
        }
    }

    public List<CitizenView> citizensNear(Vector3d position, double radius) {
        if (!available || citizensManager == null || position == null) {
            return List.of();
        }
        try {
            Object result = citizensManager.getClass().getMethod("getCitizensNear", Vector3d.class, double.class)
                    .invoke(citizensManager, position, radius);
            return toCitizenViews(result);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Failed to list nearby HyCitizens citizens.");
            return List.of();
        }
    }

    public Optional<CitizenView> citizen(String id) {
        if (!available || citizensManager == null || id == null || id.isBlank()) {
            return Optional.empty();
        }
        try {
            Object citizen = citizensManager.getClass().getMethod("getCitizen", String.class).invoke(citizensManager, id);
            return citizen == null ? Optional.empty() : Optional.of(toCitizenView(citizen));
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Failed to read HyCitizens citizen " + id + ".");
            return Optional.empty();
        }
    }

    @Override
    public void close() {
        if (!available || citizensManager == null || interactListener == null || removeInteractListenerMethod == null) {
            return;
        }
        try {
            removeInteractListenerMethod.invoke(citizensManager, interactListener);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            logger.at(Level.FINE).withCause(exception).log("Failed to unregister HyCitizens listener.");
        } finally {
            available = false;
            interactListener = null;
            citizensManager = null;
            removeInteractListenerMethod = null;
        }
    }

    private List<CitizenView> toCitizenViews(Object result) {
        if (!(result instanceof Collection<?> collection)) {
            return List.of();
        }
        List<CitizenView> citizens = new ArrayList<>();
        for (Object citizen : collection) {
            if (citizen != null) {
                citizens.add(toCitizenView(citizen));
            }
        }
        return citizens;
    }

    private CitizenView toCitizenView(Object citizen) {
        Vector3d position = invokeVector(citizen, "getCurrentPosition")
                .orElseGet(() -> invokeVector(citizen, "getPosition").orElse(null));
        return new CitizenView(
                invokeString(citizen, "getId"),
                invokeString(citizen, "getName"),
                invokeString(citizen, "getGroup"),
                invokeUuid(citizen, "getSpawnedUUID"),
                invokeUuid(citizen, "getWorldUUID"),
                position == null ? Double.NaN : position.x,
                position == null ? Double.NaN : position.y,
                position == null ? Double.NaN : position.z,
                Objects.toString(invoke(citizen, "getNpcRef"), ""));
    }

    private Object invoke(Object target, String methodName) {
        try {
            return target.getClass().getMethod(methodName).invoke(target);
        } catch (ReflectiveOperationException | RuntimeException exception) {
            return null;
        }
    }

    private String invokeString(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value == null ? "" : value.toString();
    }

    private String invokeUuid(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof UUID uuid ? uuid.toString() : Objects.toString(value, "");
    }

    private Optional<Vector3d> invokeVector(Object target, String methodName) {
        Object value = invoke(target, methodName);
        return value instanceof Vector3d vector ? Optional.of(vector) : Optional.empty();
    }

    private final class CitizenInteractInvocationHandler implements InvocationHandler {
        @Override
        public Object invoke(Object proxy, Method method, Object[] args) throws Throwable {
            if (method.getDeclaringClass() == Object.class) {
                return method.invoke(this, args);
            }
            if (!"onCitizenInteract".equals(method.getName()) || args == null || args.length == 0 || args[0] == null) {
                return null;
            }
            Object event = args[0];
            Object citizen = event.getClass().getMethod("getCitizen").invoke(event);
            Object player = event.getClass().getMethod("getPlayer").invoke(event);
            if (!(player instanceof PlayerRef playerRef) || citizen == null) {
                return null;
            }
            CitizenView view = toCitizenView(citizen);
            if (conversationService.tryStartHyCitizen(playerRef, view)) {
                event.getClass().getMethod("setCancelled", boolean.class).invoke(event, true);
            }
            return null;
        }
    }

    public record CitizenView(
            String id,
            String name,
            String group,
            String spawnedUuid,
            String worldUuid,
            double x,
            double y,
            double z,
            String npcRef) {
    }
}
