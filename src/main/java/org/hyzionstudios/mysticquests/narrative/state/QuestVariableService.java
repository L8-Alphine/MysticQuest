package org.hyzionstudios.mysticquests.narrative.state;

import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.value.Coercion;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DoubleValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DurationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LongValue;
import org.hyzionstudios.mysticquests.narrative.value.TypeSpec;
import org.hyzionstudios.mysticquests.narrative.value.ValueCodec;
import org.hyzionstudios.mysticquests.narrative.value.ValueType;

import com.fasterxml.jackson.databind.JsonNode;

import javax.annotation.Nullable;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Consumer;
import java.util.function.Supplier;

/**
 * Typed, scope-aware narrative variables (§4.2 of the 2.0 specification).
 *
 * <p>Writes are checked against the declared {@link TypeSpec} and refused, with the variable's name,
 * when they do not fit. Reads apply the declared default.
 *
 * <h2>Stored values that no longer fit</h2>
 *
 * <p>A release can change a variable's type while players still hold values written under the old
 * one (§26.2, "quest content version changes while a player has persisted state"). A read then
 * tries to convert the stored value through its text form, so an old {@code "4"} still reads as the
 * integer 4. If that fails it returns the default and reports a {@code MIGRATION} problem. A read
 * never rewrites storage, so the original value survives until something deliberately overwrites it.
 */
public final class QuestVariableService {
    private final Supplier<SchemaRegistry> schemas;
    private final ScopeResolver resolver;
    private final StateHost host;
    private final Consumer<String> problems;

    public QuestVariableService(
            Supplier<SchemaRegistry> schemas,
            ScopeResolver resolver,
            StateHost host,
            Consumer<String> problems) {
        this.schemas = schemas;
        this.resolver = resolver;
        this.host = host;
        this.problems = problems;
    }

    /** The current value, or the declared default; empty when unset with no default, or unreadable. */
    public Optional<QuestValue> get(ScopeContext context, @Nullable VariableScope scope, NamespacedId id) {
        Target target = target(context, scope, id);
        if (target.problem != null) {
            return Optional.empty();
        }
        return Optional.ofNullable(current(target, id));
    }

    @Nullable
    private QuestValue current(Target target, NamespacedId id) {
        QuestValue stored = target.state.variable(id);
        if (stored == null) {
            return target.schema.defaultValue();
        }
        if (ValueCodec.conforms(target.schema.type(), stored)) {
            return stored;
        }
        Coercion converted = ValueCodec.coerceText(target.schema.type(), ValueCodec.toText(stored));
        if (converted.accepted()) {
            return converted.value();
        }
        problems.accept(DiagnosticCode.MIGRATION + " " + id + " holds a stored " + stored.type().id()
                + " that cannot be read as " + target.schema.type() + "; using the default instead");
        return target.schema.defaultValue();
    }

    public StateResult set(ScopeContext context, @Nullable VariableScope scope, NamespacedId id, QuestValue value) {
        Target target = target(context, scope, id);
        if (target.problem != null) {
            return target.problem;
        }
        if (!ValueCodec.conforms(target.schema.type(), value)) {
            return StateResult.rejected(DiagnosticCode.INVALID_VARIABLE_TYPE,
                    "variable " + id + " is " + target.schema.type() + " but was given a " + value.type().id());
        }
        return target.state.putVariable(id, value) ? StateResult.changed(value) : StateResult.unchanged(value);
    }

    /** Coerces authored or command input onto the declared type, then sets it. */
    public StateResult setRaw(ScopeContext context, @Nullable VariableScope scope, NamespacedId id, JsonNode raw) {
        Target target = target(context, scope, id);
        if (target.problem != null) {
            return target.problem;
        }
        Coercion coerced = ValueCodec.coerce(target.schema.type(), raw);
        if (!coerced.accepted()) {
            return StateResult.rejected(DiagnosticCode.INVALID_VARIABLE_TYPE, "variable " + id + ": " + coerced.problem());
        }
        return set(context, scope, id, coerced.value());
    }

    /**
     * Adds {@code delta} atomically. Integer, long, double and duration variables can be
     * incremented; integer and long overflow is refused rather than wrapped, because a wrapped
     * counter would silently pass or fail a threshold condition.
     */
    public StateResult increment(ScopeContext context, @Nullable VariableScope scope, NamespacedId id, QuestValue delta) {
        Target target = target(context, scope, id);
        if (target.problem != null) {
            return target.problem;
        }
        TypeSpec type = target.schema.type();
        if (!type.type().numeric() && type.type() != ValueType.DURATION) {
            return StateResult.rejected(DiagnosticCode.INVALID_VARIABLE_TYPE,
                    "variable " + id + " is " + type + " and cannot be incremented");
        }
        Coercion coercedDelta = ValueCodec.coerceText(type, ValueCodec.toText(delta));
        if (!coercedDelta.accepted()) {
            return StateResult.rejected(DiagnosticCode.INVALID_VARIABLE_TYPE,
                    "increment for " + id + ": " + coercedDelta.problem());
        }
        AtomicReference<String> overflow = new AtomicReference<>();
        QuestValue start = current(target, id);
        QuestValue base = start != null ? start : ValueCodec.zero(type);
        QuestValue updated = target.state.computeVariable(id, existing -> {
            QuestValue from = existing != null && ValueCodec.conforms(type, existing) ? existing : base;
            try {
                return add(from, coercedDelta.value());
            } catch (ArithmeticException tooLarge) {
                overflow.set(tooLarge.getMessage());
                return existing;
            }
        });
        if (overflow.get() != null) {
            return StateResult.rejected(DiagnosticCode.INVALID_PARAMETER, "increment for " + id + " overflows " + type);
        }
        return StateResult.changed(updated);
    }

    private static QuestValue add(QuestValue current, QuestValue delta) {
        return switch (current) {
            case IntValue integer -> new IntValue(Math.addExact(integer.value(), ((IntValue) delta).value()));
            case LongValue longValue -> new LongValue(Math.addExact(longValue.value(), ((LongValue) delta).value()));
            case DoubleValue doubleValue -> new DoubleValue(doubleValue.value() + ((DoubleValue) delta).value());
            case DurationValue duration -> new DurationValue(duration.value().plus(((DurationValue) delta).value()));
            default -> throw new IllegalStateException("not incrementable: " + current.type());
        };
    }

    public StateResult remove(ScopeContext context, @Nullable VariableScope scope, NamespacedId id) {
        Target target = target(context, scope, id);
        if (target.problem != null) {
            return target.problem;
        }
        return target.state.removeVariable(id) ? StateResult.changed(null) : StateResult.unchanged(null);
    }

    /** Why an operation on this variable in this context would be refused, or null. */
    @Nullable
    public StateResult check(ScopeContext context, @Nullable VariableScope scope, NamespacedId id) {
        return target(context, scope, id).problem;
    }

    private Target target(ScopeContext context, @Nullable VariableScope requested, NamespacedId id) {
        VariableSchema schema = schemas.get().variable(id);
        if (schema == null) {
            return Target.fail(StateResult.rejected(DiagnosticCode.UNKNOWN_VARIABLE,
                    "variable " + id + " is not declared; add it to a package's variableSchemas"));
        }
        VariableScope scope = requested != null ? requested : schema.scope() != null ? schema.scope() : VariableScope.PLAYER;
        if (!schema.allows(scope)) {
            return Target.fail(StateResult.rejected(DiagnosticCode.INVALID_SCOPE,
                    "variable " + id + " is declared in " + schema.scope().id() + " scope, not " + scope.id()));
        }
        ScopeResolver.Resolution resolution = resolver.resolve(scope, context);
        if (!resolution.resolved()) {
            DiagnosticCode code = resolver.support().usable(scope)
                    ? DiagnosticCode.SCOPE_UNRESOLVED
                    : DiagnosticCode.UNSUPPORTED_SCOPE;
            return Target.fail(StateResult.rejected(code, "variable " + id + ": " + resolution.problem()));
        }
        OwnerState state = host.state(resolution.owner());
        if (state == null) {
            return Target.fail(StateResult.rejected(DiagnosticCode.SCOPE_UNRESOLVED,
                    "variable " + id + ": " + resolution.owner() + " is not open"));
        }
        return new Target(schema, state, null);
    }

    private record Target(VariableSchema schema, OwnerState state, @Nullable StateResult problem) {
        static Target fail(StateResult problem) {
            return new Target(null, null, problem);
        }
    }
}
