package org.hyzionstudios.mysticquests.narrative.value;

import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DoubleValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.DurationValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.IntValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.ListValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.LongValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.MapValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.SetValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.StringValue;
import org.hyzionstudios.mysticquests.narrative.value.QuestValue.TimestampValue;

import javax.annotation.Nullable;
import java.math.BigDecimal;
import java.util.Locale;

/** Comparison operators for variable conditions, with their typed semantics. */
public enum CompareOperator {
    EQ("=="),
    NE("!="),
    GT(">"),
    GTE(">="),
    LT("<"),
    LTE("<="),
    /** Collection membership, map key presence, or substring for strings. */
    CONTAINS("contains");

    private final String symbol;

    CompareOperator(String symbol) {
        this.symbol = symbol;
    }

    public String symbol() {
        return symbol;
    }

    /** Whether this operator needs an ordered type; validation refuses it on anything else. */
    public boolean ordering() {
        return this == GT || this == GTE || this == LT || this == LTE;
    }

    /** Parses {@code ==}, {@code eq}, {@code >=}, {@code gte} and the rest. Null when unknown. */
    @Nullable
    public static CompareOperator parse(@Nullable String raw) {
        if (raw == null) {
            return null;
        }
        return switch (raw.trim().toLowerCase(Locale.ROOT)) {
            case "==", "=", "eq", "equals" -> EQ;
            case "!=", "<>", "ne", "neq", "not" -> NE;
            case ">", "gt" -> GT;
            case ">=", "gte", "ge" -> GTE;
            case "<", "lt" -> LT;
            case "<=", "lte", "le" -> LTE;
            case "contains", "has", "in" -> CONTAINS;
            default -> null;
        };
    }

    /**
     * Applies this operator. Numbers compare by value across integer, long and double, so an
     * {@code integer} variable equal to 4 matches a literal {@code 4.0}. Comparisons that make no
     * sense for the types involved are false rather than an error, because validation has already
     * refused them in authored content and a runtime throw would abort the surrounding transition.
     */
    public boolean test(@Nullable QuestValue actual, QuestValue expected) {
        if (actual == null) {
            return this == NE;
        }
        return switch (this) {
            case EQ -> equal(actual, expected);
            case NE -> !equal(actual, expected);
            case CONTAINS -> contains(actual, expected);
            default -> {
                Integer order = order(actual, expected);
                yield order != null && switch (this) {
                    case GT -> order > 0;
                    case GTE -> order >= 0;
                    case LT -> order < 0;
                    default -> order <= 0;
                };
            }
        };
    }

    private static boolean equal(QuestValue left, QuestValue right) {
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber) == 0;
        }
        return left.equals(right);
    }

    @Nullable
    private static Integer order(QuestValue left, QuestValue right) {
        BigDecimal leftNumber = number(left);
        BigDecimal rightNumber = number(right);
        if (leftNumber != null && rightNumber != null) {
            return leftNumber.compareTo(rightNumber);
        }
        if (left instanceof DurationValue a && right instanceof DurationValue b) {
            return a.value().compareTo(b.value());
        }
        if (left instanceof TimestampValue a && right instanceof TimestampValue b) {
            return a.value().compareTo(b.value());
        }
        return null;
    }

    private static boolean contains(QuestValue container, QuestValue element) {
        return switch (container) {
            case ListValue list -> list.values().stream().anyMatch(value -> equal(value, element));
            case SetValue set -> set.values().stream().anyMatch(value -> equal(value, element));
            case MapValue map -> element instanceof StringValue key && map.values().containsKey(key.value());
            case StringValue string -> element instanceof StringValue part && string.value().contains(part.value());
            default -> false;
        };
    }

    @Nullable
    private static BigDecimal number(QuestValue value) {
        return switch (value) {
            case IntValue integer -> BigDecimal.valueOf(integer.value());
            case LongValue longValue -> BigDecimal.valueOf(longValue.value());
            case DoubleValue doubleValue -> BigDecimal.valueOf(doubleValue.value());
            default -> null;
        };
    }
}
