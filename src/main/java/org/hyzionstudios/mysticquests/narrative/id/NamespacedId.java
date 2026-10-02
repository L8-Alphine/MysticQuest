package org.hyzionstudios.mysticquests.narrative.id;

import javax.annotation.Nullable;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Pattern;

/**
 * A stable {@code namespace:path} identifier for tags, variables, action and condition types,
 * puzzles, and anything else the narrative runtime persists by name.
 *
 * <p>v1 tags were bare strings, so two packages that both wrote {@code completed} shared one flag and
 * nothing could tell them apart. Requiring a namespace makes ownership explicit
 * ({@code hyzion:druid_temple.entered}) and lets validation reject a typo instead of creating a new
 * flag nobody reads.
 *
 * <p>Both halves are lower case. The namespace is letters, digits and {@code _}, joined by single
 * {@code -}; the path additionally allows single {@code .} and {@code /} separators, so a tag can be
 * grouped ({@code avalon.discovered}) without a second identifier type.
 */
public record NamespacedId(String namespace, String path) implements Comparable<NamespacedId> {
    public static final int MAX_LENGTH = 128;

    private static final Pattern NAMESPACE = Pattern.compile("[a-z0-9_]+(?:-[a-z0-9_]+)*");
    private static final Pattern PATH = Pattern.compile("[a-z0-9_]+(?:[./-][a-z0-9_]+)*");

    public NamespacedId {
        Objects.requireNonNull(namespace, "namespace");
        Objects.requireNonNull(path, "path");
        String problem = problem(namespace, path);
        if (problem != null) {
            throw new IllegalArgumentException(problem);
        }
    }

    public static NamespacedId of(String namespace, String path) {
        return new NamespacedId(namespace, path);
    }

    /**
     * Parses {@code namespace:path}.
     *
     * @throws IllegalArgumentException with a message that says what is wrong and how to write it
     */
    public static NamespacedId parse(String raw) {
        if (raw == null || raw.isBlank()) {
            throw new IllegalArgumentException("Identifier is blank; expected 'namespace:path'.");
        }
        String trimmed = raw.trim();
        int colon = trimmed.indexOf(':');
        if (colon < 0) {
            throw new IllegalArgumentException("Identifier '" + trimmed
                    + "' has no namespace; write it as 'yournamespace:" + trimmed + "'.");
        }
        if (trimmed.indexOf(':', colon + 1) >= 0) {
            throw new IllegalArgumentException("Identifier '" + trimmed + "' has more than one ':'.");
        }
        return new NamespacedId(trimmed.substring(0, colon), trimmed.substring(colon + 1));
    }

    /** {@link #parse} without the exception, for call sites that report their own diagnostic. */
    public static Optional<NamespacedId> tryParse(@Nullable String raw) {
        try {
            return Optional.of(parse(raw));
        } catch (IllegalArgumentException invalid) {
            return Optional.empty();
        }
    }

    /**
     * Why {@code raw} is not a valid identifier, or null when it is. Lets validation report the exact
     * problem without catching exceptions.
     */
    @Nullable
    public static String describeProblem(@Nullable String raw) {
        try {
            parse(raw);
            return null;
        } catch (IllegalArgumentException invalid) {
            return invalid.getMessage();
        }
    }

    /**
     * Parses an identifier that may predate namespacing, placing a bare name in
     * {@code defaultNamespace}. Only the legacy migration path uses this: new content must name its
     * namespace.
     *
     * <p>Characters outside the path alphabet are folded to {@code _} and the result is lower-cased,
     * because v1 accepted any string as a tag. The fold is lossy, which is why the migrator reports
     * every identifier it had to change.
     */
    public static NamespacedId fromLegacy(String raw, String defaultNamespace) {
        Optional<NamespacedId> parsed = tryParse(raw);
        if (parsed.isPresent()) {
            return parsed.get();
        }
        String folded = raw == null ? "" : raw.trim().toLowerCase(java.util.Locale.ROOT)
                .replaceAll("[^a-z0-9_./-]", "_")
                .replaceAll("[./-]{2,}", "_")
                .replaceAll("^[./-]+|[./-]+$", "");
        int room = MAX_LENGTH - defaultNamespace.length() - 1;
        if (folded.length() > room) {
            folded = folded.substring(0, room).replaceAll("[./-]+$", "");
        }
        if (folded.isEmpty()) {
            folded = "_";
        }
        return new NamespacedId(defaultNamespace, folded);
    }

    @Nullable
    private static String problem(String namespace, String path) {
        if (namespace.length() + path.length() + 1 > MAX_LENGTH) {
            return "Identifier '" + namespace + ":" + path + "' is longer than " + MAX_LENGTH + " characters.";
        }
        if (!NAMESPACE.matcher(namespace).matches()) {
            return "Namespace '" + namespace + "' must be lower-case letters, digits, '_' or single '-'.";
        }
        if (!PATH.matcher(path).matches()) {
            return "Path '" + path + "' must be lower-case letters, digits, '_' and single '.', '/' or '-' separators.";
        }
        return null;
    }

    @Override
    public int compareTo(NamespacedId other) {
        int byNamespace = namespace.compareTo(other.namespace);
        return byNamespace != 0 ? byNamespace : path.compareTo(other.path);
    }

    @Override
    public String toString() {
        return namespace + ":" + path;
    }
}
