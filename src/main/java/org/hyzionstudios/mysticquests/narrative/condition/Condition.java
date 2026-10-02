package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.id.NamespacedId;
import org.hyzionstudios.mysticquests.narrative.state.VariableScope;
import org.hyzionstudios.mysticquests.narrative.value.CompareOperator;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue;

import com.fasterxml.jackson.databind.node.ObjectNode;

import javax.annotation.Nullable;
import java.util.List;
import java.util.Objects;

/**
 * A compiled, immutable condition tree (§4.3 of the 2.0 specification).
 *
 * <p>Content is compiled into these once per reload by {@link ConditionCompiler}, which also does the
 * static checks. Evaluation then walks plain records: no JSON lookups, no string parsing, and no
 * repeated validation on the hot path (§28, "cache compiled condition graphs").
 *
 * <p>Combinator semantics:
 * <ul>
 *   <li>{@link All}, {@link Any}, {@link None}: every, at least one, and no child is true.</li>
 *   <li>{@link Xor}: exactly one of <em>two</em> children. N-ary XOR is ambiguous (parity, or exactly
 *       one?), so it is limited to two; {@link Exactly} with a count of 1 covers the n-ary case
 *       unambiguously.</li>
 *   <li>{@link AtLeast}, {@link AtMost}, {@link Exactly}: count how many children are true.</li>
 * </ul>
 */
public sealed interface Condition {

    record All(List<Condition> children) implements Condition {
        public All {
            children = List.copyOf(children);
        }
    }

    record Any(List<Condition> children) implements Condition {
        public Any {
            children = List.copyOf(children);
        }
    }

    record None(List<Condition> children) implements Condition {
        public None {
            children = List.copyOf(children);
        }
    }

    record Not(Condition child) implements Condition {
        public Not {
            Objects.requireNonNull(child, "child");
        }
    }

    record Xor(Condition left, Condition right) implements Condition {
        public Xor {
            Objects.requireNonNull(left, "left");
            Objects.requireNonNull(right, "right");
        }
    }

    record AtLeast(int count, List<Condition> children) implements Condition {
        public AtLeast {
            children = List.copyOf(children);
        }
    }

    record AtMost(int count, List<Condition> children) implements Condition {
        public AtMost {
            children = List.copyOf(children);
        }
    }

    record Exactly(int count, List<Condition> children) implements Condition {
        public Exactly {
            children = List.copyOf(children);
        }
    }

    /** @param scope null uses the tag's declared scope */
    record TagExists(@Nullable VariableScope scope, NamespacedId tag) implements Condition {
        public TagExists {
            Objects.requireNonNull(tag, "tag");
        }
    }

    /** Compares a variable's current value (or its default) with an operand already coerced to its type. */
    record VariableCompare(@Nullable VariableScope scope, NamespacedId variable, CompareOperator operator, QuestValue operand)
            implements Condition {
        public VariableCompare {
            Objects.requireNonNull(variable, "variable");
            Objects.requireNonNull(operator, "operator");
            Objects.requireNonNull(operand, "operand");
        }
    }

    /** True when the variable has a value, counting a declared default. */
    record VariableExists(@Nullable VariableScope scope, NamespacedId variable) implements Condition {
        public VariableExists {
            Objects.requireNonNull(variable, "variable");
        }
    }

    /**
     * A condition type registered by MysticQuests or another mod.
     *
     * @param path where it was authored, so a failing handler can be reported against its content
     */
    record Custom(NamespacedId type, ObjectNode parameters, String path) implements Condition {
        public Custom {
            Objects.requireNonNull(type, "type");
            parameters = parameters.deepCopy();
        }
    }

    /** What an invalid subtree compiles to, so one bad leaf cannot make a whole tree pass. */
    record Constant(boolean value) implements Condition {
    }
}
