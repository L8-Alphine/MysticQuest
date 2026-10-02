package org.hyzionstudios.mysticquests.event;

import com.hypixel.hytale.logger.HytaleLogger;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;
import java.util.logging.Level;

/**
 * Typed synchronous event bus for MysticQuests state-change notifications.
 *
 * <p>Listeners run on the thread that posts the event, which keeps quest scripting deterministic:
 * a tag added by an event is visible to everything that reacts to it before the next event runs.
 * Each listener is isolated in a try/catch so one misbehaving subscriber — most likely a third-party
 * mod holding a handle from the public API — cannot break dispatch for anyone else.
 *
 * <p>Subscription and dispatch are lock-free. Dispatch is keyed on the event's exact runtime class,
 * so posting is a single map lookup and callers pay nothing when nobody is listening.
 */
public final class MysticQuestsEventBus {
    private final Map<Class<?>, CopyOnWriteArrayList<Consumer<?>>> listeners = new ConcurrentHashMap<>();

    /** Null outside a running server, where there is no plugin logger to report a bad listener to. */
    @Nullable
    private final HytaleLogger logger;

    public MysticQuestsEventBus(@Nullable HytaleLogger logger) {
        this.logger = logger;
    }

    /**
     * Registers a listener for events of exactly {@code type}. Subtypes are not matched, so a
     * listener for a supertype never sees a subclass event.
     *
     * @return a handle whose {@link AutoCloseable#close()} removes the listener
     */
    public <E> AutoCloseable subscribe(Class<E> type, Consumer<E> listener) {
        if (type == null || listener == null) {
            return () -> { };
        }
        listeners.computeIfAbsent(type, ignored -> new CopyOnWriteArrayList<>()).add(listener);
        return () -> unsubscribe(type, listener);
    }

    public <E> void unsubscribe(Class<E> type, Consumer<E> listener) {
        CopyOnWriteArrayList<Consumer<?>> registered = listeners.get(type);
        if (registered != null) {
            registered.remove(listener);
        }
    }

    /** Returns true when at least one listener is registered for the exact event type. */
    public boolean hasListeners(Class<?> type) {
        CopyOnWriteArrayList<Consumer<?>> registered = listeners.get(type);
        return registered != null && !registered.isEmpty();
    }

    /** Posts an event to every listener registered for its exact runtime type. */
    @SuppressWarnings("unchecked")
    public <E> void post(E event) {
        if (event == null) {
            return;
        }
        CopyOnWriteArrayList<Consumer<?>> registered = listeners.get(event.getClass());
        if (registered == null || registered.isEmpty()) {
            return;
        }
        for (Consumer<?> listener : registered) {
            try {
                ((Consumer<E>) listener).accept(event);
            } catch (RuntimeException exception) {
                if (logger != null) {
                    logger.at(Level.WARNING).withCause(exception).log(
                            "MysticQuests listener for " + event.getClass().getSimpleName() + " threw; continuing.");
                }
            }
        }
    }

    public void clear() {
        listeners.clear();
    }
}
