package org.hyzionstudios.mysticquests.ui;

import java.util.Collection;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Set;

/**
 * Custom HUD keys players never see: other mods' layers that MysticQuests replaces, such as their
 * quest trackers. A key ending in {@code *} hides every key with that prefix; a lone {@code *} is
 * ignored, since hiding every other mod's HUD is never what a server owner meant.
 *
 * <p>MysticQuests' own HUD keys can never be hidden, so a careless {@code mysticquests:*} cannot take
 * away the tracker that is replacing everything else.
 */
public final class HiddenHudKeys {
    public static final HiddenHudKeys NONE = new HiddenHudKeys(Set.of(), List.of());
    private static final String OWN_PREFIX = "mysticquests:";

    private final Set<String> exact;
    private final List<String> prefixes;

    private HiddenHudKeys(Set<String> exact, List<String> prefixes) {
        this.exact = Set.copyOf(exact);
        this.prefixes = List.copyOf(prefixes);
    }

    public static HiddenHudKeys of(Collection<String> keys) {
        Set<String> exact = new LinkedHashSet<>();
        Set<String> prefixes = new LinkedHashSet<>();
        for (String raw : keys) {
            if (raw == null || raw.isBlank()) {
                continue;
            }
            String key = raw.strip();
            if (key.endsWith("*")) {
                String prefix = key.substring(0, key.length() - 1);
                if (!prefix.isEmpty()) {
                    prefixes.add(prefix);
                }
            } else {
                exact.add(key);
            }
        }
        return new HiddenHudKeys(exact, List.copyOf(prefixes));
    }

    /** Both sets of keys together; nothing hidden by either is shown by the result. */
    public HiddenHudKeys plus(HiddenHudKeys other) {
        Set<String> keys = new LinkedHashSet<>(describe());
        keys.addAll(other.describe());
        return of(keys);
    }

    public boolean hides(String hudKey) {
        if (hudKey == null || hudKey.startsWith(OWN_PREFIX)) {
            return false;
        }
        if (exact.contains(hudKey)) {
            return true;
        }
        for (String prefix : prefixes) {
            if (hudKey.startsWith(prefix)) {
                return true;
            }
        }
        return false;
    }

    public boolean isEmpty() {
        return exact.isEmpty() && prefixes.isEmpty();
    }

    /** The keys as configured, prefixes with their {@code *}, for diagnostics. */
    public Set<String> describe() {
        Set<String> keys = new LinkedHashSet<>(exact);
        prefixes.forEach(prefix -> keys.add(prefix + "*"));
        return keys;
    }
}
