package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;

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

    public Boolean getInvert() {
        return invert;
    }

    public void setInvert(Boolean invert) {
        this.invert = invert;
    }

    protected boolean maybeInvert(boolean result) {
        return Boolean.TRUE.equals(invert) ? !result : result;
    }
}
