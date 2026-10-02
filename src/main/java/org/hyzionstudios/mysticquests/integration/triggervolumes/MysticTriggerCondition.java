package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;

import java.util.Locale;
import java.util.Map;

public abstract class MysticTriggerCondition extends TriggerCondition {
    private String scope = "player";
    private String target;
    private String tag;
    private String key;
    private String value;
    private String operator = "eq";
    private Boolean invert;

    public String getScope() {
        return scope;
    }

    public void setScope(String scope) {
        this.scope = scope;
    }

    public MysticTriggerScope getScopeOption() {
        return MysticTriggerScope.parse(scope);
    }

    public void setScopeOption(MysticTriggerScope scope) {
        this.scope = scope == null ? MysticTriggerScope.player.name() : scope.name();
    }

    public String getTarget() {
        return target;
    }

    public void setTarget(String target) {
        this.target = target;
    }

    public String getTag() {
        return tag == null ? "" : tag;
    }

    public void setTag(String tag) {
        this.tag = tag;
    }

    public String getKey() {
        return key == null ? "" : key;
    }

    public void setKey(String key) {
        this.key = key;
    }

    public String getValue() {
        return value == null ? "" : value;
    }

    public void setValue(String value) {
        this.value = value;
    }

    public String getOperator() {
        return operator == null ? "eq" : operator;
    }

    public void setOperator(String operator) {
        this.operator = operator;
    }

    public VariableOperator getOperatorOption() {
        return VariableOperator.parse(operator);
    }

    public void setOperatorOption(VariableOperator operator) {
        this.operator = operator == null ? VariableOperator.eq.name() : operator.name();
    }

    public Boolean getInvert() {
        return invert;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }

    protected boolean maybeInvert(boolean result) {
        return Boolean.TRUE.equals(invert) ? !result : result;
    }

    public enum VariableOperator {
        eq,
        ne,
        gt,
        gte,
        lt,
        lte,
        exists;

        public static final Map<VariableOperator, String> ALIASES = Map.of(
                eq, "eq",
                ne, "ne",
                gt, "gt",
                gte, "gte",
                lt, "lt",
                lte, "lte",
                exists, "exists");

        public static VariableOperator parse(String value) {
            if (value == null) {
                return eq;
            }
            return switch (value.trim().toLowerCase(Locale.ROOT)) {
                case "ne", "!=", "not" -> ne;
                case "gt", ">" -> gt;
                case "gte", ">=" -> gte;
                case "lt", "<" -> lt;
                case "lte", "<=" -> lte;
                case "exists" -> exists;
                default -> eq;
            };
        }
    }
}
