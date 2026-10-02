package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Registered custom condition types, by namespaced id.
 *
 * <p>Same rules as v1's {@code MysticQuestsRegistry}: the first registration of an id wins and a
 * second is refused, so load order cannot silently change what content means. The
 * {@code mysticquests} namespace is reserved for built-ins.
 */
public final class ConditionTypeRegistry {
    public static final String RESERVED_NAMESPACE = "mysticquests";

    private final Map<NamespacedId, ConditionHandler> handlers = new ConcurrentHashMap<>();

    /** Registers a third-party type. Refused (false) for a reserved namespace or a taken id. */
    public boolean register(NamespacedId type, ConditionHandler handler) {
        if (type == null || handler == null || RESERVED_NAMESPACE.equals(type.namespace())) {
            return false;
        }
        return handlers.putIfAbsent(type, handler) == null;
    }

    /** Registers a built-in type. Only the runtime calls this. */
    public void registerBuiltIn(NamespacedId type, ConditionHandler handler) {
        if (!RESERVED_NAMESPACE.equals(type.namespace())) {
            throw new IllegalArgumentException("Built-in condition types live in '" + RESERVED_NAMESPACE + "': " + type);
        }
        if (handlers.putIfAbsent(type, handler) != null) {
            throw new IllegalStateException("Built-in condition type registered twice: " + type);
        }
    }

    public boolean unregister(NamespacedId type) {
        return type != null && handlers.remove(type) != null;
    }

    @Nullable
    public ConditionHandler handler(NamespacedId type) {
        return handlers.get(type);
    }

    public Set<NamespacedId> types() {
        return Set.copyOf(handlers.keySet());
    }
}
