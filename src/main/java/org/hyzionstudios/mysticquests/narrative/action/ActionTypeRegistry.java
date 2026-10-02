package org.hyzionstudios.mysticquests.narrative.action;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registered narrative action types, by namespaced id. First registration wins; the
 * {@code mysticquests} namespace is reserved for built-ins.
 */
public final class ActionTypeRegistry {
    public static final String RESERVED_NAMESPACE = "mysticquests";

    private final Map<NamespacedId, ActionHandler> handlers = new ConcurrentHashMap<>();

    /** Registers a third-party type. Refused (false) for a reserved namespace or a taken id. */
    public boolean register(NamespacedId type, ActionHandler handler) {
        if (type == null || handler == null || RESERVED_NAMESPACE.equals(type.namespace())) {
            return false;
        }
        return handlers.putIfAbsent(type, handler) == null;
    }

    /** Registers a built-in type. Only the runtime calls this. */
    public void registerBuiltIn(NamespacedId type, ActionHandler handler) {
        if (!RESERVED_NAMESPACE.equals(type.namespace())) {
            throw new IllegalArgumentException("Built-in action types live in '" + RESERVED_NAMESPACE + "': " + type);
        }
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException("Built-in action type registered twice: " + type);
        }
    }

    public boolean unregister(NamespacedId type) {
        return type != null && handlers.remove(type) != null;
    }

    @Nullable
    public ActionHandler handler(NamespacedId type) {
        return handlers.get(type);
    }

    public Set<NamespacedId> types() {
        return Set.copyOf(handlers.keySet());
    }
}
