package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.HashSet;
import java.util.Map;
import java.util.Set;

/**
 * The declared tags and variables, plus the namespaces in which undeclared ones are tolerated.
 *
 * <p>Immutable: a content reload compiles a new registry and swaps it in whole, so a read never sees
 * half of one release's schemas and half of another's.
 *
 * <p><b>Open namespaces</b> exist for migration. v1 content wrote any tag name it liked, and the
 * legacy migrator moves those into the {@code legacy} namespace. Requiring a declaration for every
 * one of them before a server could start would make migration all-or-nothing. In an open
 * namespace, an undeclared tag is accepted in any scope, and an undeclared variable is accepted as a
 * string in any scope. Everywhere else, an undeclared id is an error.
 */
public final class SchemaRegistry {
    /** The namespace v1 state is migrated into; always open. */
    public static final String LEGACY_NAMESPACE = "legacy";

    private final Map<NamespacedId, VariableSchema> variables;
    private final Map<NamespacedId, TagSchema> tags;
    private final Set<String> openNamespaces;

    public SchemaRegistry(
            Map<NamespacedId, VariableSchema> variables,
            Map<NamespacedId, TagSchema> tags,
            Collection<String> openNamespaces) {
        this.variables = Map.copyOf(variables);
        this.tags = Map.copyOf(tags);
        Set<String> open = new HashSet<>(openNamespaces);
        open.add(LEGACY_NAMESPACE);
        this.openNamespaces = Set.copyOf(open);
    }

    public static SchemaRegistry empty() {
        return new SchemaRegistry(Map.of(), Map.of(), Set.of());
    }

    /**
     * The schema governing {@code id}: its declaration, or a permissive string schema when the id is
     * undeclared in an open namespace. Null means the id is unknown, and the caller must report it.
     */
    @Nullable
    public VariableSchema variable(NamespacedId id) {
        VariableSchema declared = variables.get(id);
        if (declared != null) {
            return declared;
        }
        return isOpen(id) ? new VariableSchema(id, TypeSpec.of(ValueType.STRING), null, null, "") : null;
    }

    /** As {@link #variable}, for tags. */
    @Nullable
    public TagSchema tag(NamespacedId id) {
        TagSchema declared = tags.get(id);
        if (declared != null) {
            return declared;
        }
        return isOpen(id) ? new TagSchema(id, null, null, "") : null;
    }

    public boolean isDeclaredVariable(NamespacedId id) {
        return variables.containsKey(id);
    }

    public boolean isDeclaredTag(NamespacedId id) {
        return tags.containsKey(id);
    }

    public boolean isOpen(NamespacedId id) {
        return openNamespaces.contains(id.namespace());
    }

    public Collection<VariableSchema> variables() {
        return variables.values();
    }

    public Collection<TagSchema> tags() {
        return tags.values();
    }

    public Set<String> openNamespaces() {
        return openNamespaces;
    }
}
