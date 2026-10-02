package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;

import javax.annotation.Nullable;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.function.Supplier;

/**
 * Namespaced, scope-aware tags with expiry and provenance (§4.1 of the 2.0 specification).
 *
 * <p>Every operation validates the tag against the current {@link SchemaRegistry} and resolves the
 * owner through the {@link ScopeResolver}. An unknown tag, a tag used outside its declared scope, or a
 * scope with no owner in this situation is refused with a code, never applied somewhere else.
 */
public final class QuestTagService {
    private final Supplier<SchemaRegistry> schemas;
    private final ScopeResolver resolver;
    private final StateHost host;
    private final Clock clock;

    public QuestTagService(Supplier<SchemaRegistry> schemas, ScopeResolver resolver, StateHost host, Clock clock) {
        this.schemas = schemas;
        this.resolver = resolver;
        this.host = host;
        this.clock = clock;
    }

    /**
     * Adds a tag, or refreshes its expiry if it is already present.
     *
     * @param scope where to put it; null uses the tag's declared scope
     * @param ttl how long it lasts; null uses the declared default, which may be "forever"
     * @param source what is adding it, recorded for the debugger
     */
    public StateResult add(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag,
                           @Nullable Duration ttl, String source) {
        Target target = target(context, scope, tag);
        return target.problem != null ? target.problem : addTo(target, tag, ttl, source, clock.instant());
    }

    private StateResult addTo(Target target, NamespacedId tag, @Nullable Duration ttl, String source, Instant now) {
        Duration lifetime = ttl != null ? ttl : target.schema.defaultTtl();
        if (lifetime != null && (lifetime.isNegative() || lifetime.isZero())) {
            return StateResult.rejected(DiagnosticCode.INVALID_PARAMETER, "tag " + tag + " ttl must be positive");
        }
        TagRecord record = new TagRecord(tag, now, lifetime == null ? null : now.plus(lifetime), source);
        return target.state.putTag(record, now) ? StateResult.changed(null) : StateResult.unchanged(null);
    }

    public StateResult remove(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag) {
        Target target = target(context, scope, tag);
        if (target.problem != null) {
            return target.problem;
        }
        return target.state.removeTag(tag, clock.instant()) ? StateResult.changed(null) : StateResult.unchanged(null);
    }

    /** Removes the tag if present, otherwise adds it with its default expiry. */
    public StateResult toggle(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag, String source) {
        Target target = target(context, scope, tag);
        if (target.problem != null) {
            return target.problem;
        }
        Instant now = clock.instant();
        if (target.state.tag(tag, now).isPresent()) {
            target.state.removeTag(tag, now);
            return StateResult.changed(null);
        }
        return addTo(target, tag, null, source, now);
    }

    /** Whether the tag is live. Unknown tags and unresolvable scopes read as absent. */
    public boolean exists(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag) {
        return find(context, scope, tag).isPresent();
    }

    public Optional<TagRecord> find(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag) {
        Target target = target(context, scope, tag);
        return target.problem != null ? Optional.empty() : target.state.tag(tag, clock.instant());
    }

    /** Every live tag on one owner, for the debugger. */
    public List<TagRecord> tags(ScopeOwner owner) {
        OwnerState state = host.state(owner);
        return state == null ? List.of() : state.liveTags(clock.instant());
    }

    /**
     * Why a tag operation in this context would be refused, or null when it would proceed. Lets
     * condition evaluation report the reason a tag read as absent.
     */
    @Nullable
    public StateResult check(ScopeContext context, @Nullable VariableScope scope, NamespacedId tag) {
        return target(context, scope, tag).problem;
    }

    private Target target(ScopeContext context, @Nullable VariableScope requested, NamespacedId tag) {
        TagSchema schema = schemas.get().tag(tag);
        if (schema == null) {
            return Target.fail(StateResult.rejected(DiagnosticCode.UNKNOWN_TAG,
                    "tag " + tag + " is not declared; add it to a package's tagSchemas"));
        }
        VariableScope scope = requested != null ? requested : schema.scope() != null ? schema.scope() : VariableScope.PLAYER;
        if (!schema.allows(scope)) {
            return Target.fail(StateResult.rejected(DiagnosticCode.INVALID_SCOPE,
                    "tag " + tag + " is declared in " + schema.scope().id() + " scope, not " + scope.id()));
        }
        ScopeResolver.Resolution resolution = resolver.resolve(scope, context);
        if (!resolution.resolved()) {
            DiagnosticCode code = resolver.support().usable(scope)
                    ? DiagnosticCode.SCOPE_UNRESOLVED
                    : DiagnosticCode.UNSUPPORTED_SCOPE;
            return Target.fail(StateResult.rejected(code, "tag " + tag + ": " + resolution.problem()));
        }
        OwnerState state = host.state(resolution.owner());
        if (state == null) {
            return Target.fail(StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED,
                    "tag " + tag + ": " + resolution.owner() + " is not open"));
        }
        return new Target(schema, state, null);
    }

    private record Target(TagSchema schema, OwnerState state, @Nullable StateResult problem) {
        static Target fail(StateResult problem) {
            return new Target(null, null, problem);
        }
    }
}
