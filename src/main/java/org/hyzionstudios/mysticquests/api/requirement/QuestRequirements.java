package org.hyzionstudios.mysticquests.api.requirement;

import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The registered {@link QuestRequirementProvider}s, by namespace, and the fail-closed evaluation the
 * quest engine uses for {@code requirement} conditions.
 */
public final class QuestRequirements {

    private final Map<String, QuestRequirementProvider> providers = new ConcurrentHashMap<>();

    /**
     * Registers a provider. A second provider for the same namespace is refused (load-order races must
     * not decide what content means).
     *
     * @return a handle that unregisters this provider when closed
     * @throws IllegalArgumentException for a blank namespace
     * @throws IllegalStateException when another provider holds the namespace
     */
    public AutoCloseable register(QuestRequirementProvider provider) {
        Objects.requireNonNull(provider, "provider");
        String namespace = normalize(provider.namespace());
        if (namespace.isEmpty() || namespace.contains(":")) {
            throw new IllegalArgumentException("A requirement provider needs a namespace without ':'");
        }
        if (providers.putIfAbsent(namespace, provider) != null) {
            throw new IllegalStateException("Another requirement provider already owns '" + namespace + "'");
        }
        return () -> providers.remove(namespace, provider);
    }

    public Optional<QuestRequirementProvider> provider(String namespace) {
        return Optional.ofNullable(providers.get(normalize(namespace)));
    }

    public List<String> namespaces() {
        return List.copyOf(providers.keySet());
    }

    /** Evaluates a requirement; every failure mode is UNVERIFIABLE (denies). */
    public QuestRequirementResult evaluate(UUID playerId, String namespace, String check,
                                           Map<String, String> parameters, String packageId) {
        QuestRequirementProvider provider = providers.get(normalize(namespace));
        if (provider == null) {
            return QuestRequirementResult.unverifiable("no requirement provider for '" + namespace + "'");
        }
        String name = check == null ? "" : check.trim();
        try {
            if (!provider.available()) {
                return QuestRequirementResult.unverifiable(namespace + " is not available");
            }
            if (!provider.checks().contains(name)) {
                return QuestRequirementResult.unverifiable(namespace + " has no check '" + name + "'");
            }
            QuestRequirementResult result = provider.evaluate(
                    new QuestRequirementRequest(playerId, name, parameters, packageId));
            return result == null ? QuestRequirementResult.unverifiable("no answer") : result;
        } catch (RuntimeException | LinkageError failure) {
            return QuestRequirementResult.unverifiable(namespace + " failed: " + failure);
        }
    }

    public void clear() {
        providers.clear();
    }

    private static String normalize(String namespace) {
        return namespace == null ? "" : namespace.trim().toLowerCase(Locale.ROOT);
    }
}
