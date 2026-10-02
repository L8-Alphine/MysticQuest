package org.hyzionstudios.mysticquests.narrative.condition;

import org.hyzionstudios.mysticquests.narrative.condition.Condition.All;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Any;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtLeast;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.AtMost;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Constant;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Custom;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Exactly;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.None;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Not;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.TagExists;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.VariableCompare;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.VariableExists;
import org.hyzionstudios.mysticquests.narrative.condition.Condition.Xor;
import org.hyzionstudios.mysticquests.narrative.diagnostic.DiagnosticCode;
import org.hyzionstudios.mysticquests.narrative.state.QuestTagService;
import org.hyzionstudios.mysticquests.narrative.state.QuestVariableService;
import org.hyzionstudios.mysticquests.narrative.state.ScopeContext;
import org.hyzionstudios.mysticquests.narrative.state.StateResult;

import java.util.List;
import java.util.function.Consumer;

/**
 * Evaluates compiled {@link Condition} trees deterministically.
 *
 * <p>Children are evaluated in authored order. {@link All} and {@link Any} short-circuit. The
 * counting combinators stop as soon as the answer is fixed: {@link AtLeast} once enough children
 * passed, {@link AtMost} once too many did. Evaluation has no side effects, so short-circuiting
 * never changes an outcome.
 *
 * <p>A leaf that cannot be evaluated reads as false. When that hides a fault, such as a custom
 * handler that throws or a type that was unregistered, it is also reported to {@code problems},
 * because a silent false would look like ordinary story logic. A scope with no owner in this
 * situation, such as a party tag read for a solo player, is a correct false and is not reported.
 */
public final class ConditionEvaluator {
    private final QuestTagService tags;
    private final QuestVariableService variables;
    private final ConditionTypeRegistry types;
    private final Consumer<String> problems;

    public ConditionEvaluator(
            QuestTagService tags,
            QuestVariableService variables,
            ConditionTypeRegistry types,
            Consumer<String> problems) {
        this.tags = tags;
        this.variables = variables;
        this.types = types;
        this.problems = problems;
    }

    public boolean test(Condition condition, ScopeContext context) {
        return switch (condition) {
            case All all -> all.children().stream().allMatch(child -> test(child, context));
            case Any any -> any.children().stream().anyMatch(child -> test(child, context));
            case None none -> none.children().stream().noneMatch(child -> test(child, context));
            case Not not -> !test(not.child(), context);
            case Xor xor -> test(xor.left(), context) ^ test(xor.right(), context);
            case AtLeast atLeast -> countUpTo(atLeast.children(), context, atLeast.count()) >= atLeast.count();
            case AtMost atMost -> countUpTo(atMost.children(), context, atMost.count() + 1) <= atMost.count();
            case Exactly exactly -> countUpTo(exactly.children(), context, exactly.count() + 1) == exactly.count();
            case TagExists tag -> tagExists(tag, context);
            case VariableCompare compare -> compare(compare, context);
            case VariableExists exists -> variables.get(context, exists.scope(), exists.variable()).isPresent();
            case Custom custom -> custom(custom, context);
            case Constant constant -> constant.value();
        };
    }

    /** Counts passing children, stopping once {@code limit} is reached. */
    private int countUpTo(List<Condition> children, ScopeContext context, int limit) {
        int passed = 0;
        for (Condition child : children) {
            if (passed >= limit) {
                break;
            }
            if (test(child, context)) {
                passed++;
            }
        }
        return passed;
    }

    private boolean tagExists(TagExists tag, ScopeContext context) {
        StateResult problem = tags.check(context, tag.scope(), tag.tag());
        if (problem != null) {
            report(problem);
            return false;
        }
        return tags.exists(context, tag.scope(), tag.tag());
    }

    private boolean compare(VariableCompare compare, ScopeContext context) {
        StateResult problem = variables.check(context, compare.scope(), compare.variable());
        if (problem != null) {
            report(problem);
            return false;
        }
        return compare.operator().test(
                variables.get(context, compare.scope(), compare.variable()).orElse(null),
                compare.operand());
    }

    private boolean custom(Custom custom, ScopeContext context) {
        ConditionHandler handler = types.handler(custom.type());
        if (handler == null) {
            problems.accept(DiagnosticCode.UNKNOWN_CONDITION + " " + custom.path()
                    + ": condition type " + custom.type() + " is no longer registered");
            return false;
        }
        try {
            return handler.test(context, custom.parameters());
        } catch (RuntimeException failure) {
            problems.accept(DiagnosticCode.CONDITION_FAILED + " " + custom.path() + ": " + custom.type()
                    + " threw " + failure + "; treating it as false");
            return false;
        }
    }

    private void report(StateResult problem) {
        // A read with no owner is a correct false, not a fault: a solo player's party has no tags.
        // Anything else should have been caught at load, so it is worth a line every time.
        if (problem.code() != DiagnosticCode.SCOPE_UNRESOLVED) {
            problems.accept(problem.code() + " " + problem.message());
        }
    }
}
