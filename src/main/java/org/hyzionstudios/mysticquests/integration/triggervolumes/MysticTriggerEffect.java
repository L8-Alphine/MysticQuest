package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;

public abstract class MysticTriggerEffect extends TriggerEffect {
    private String scope = "player";
    private String target;
    private String tag;
    private String key;
    private String value;
    private long amount = 1L;
    private String event;
    private String packageId = "";

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

    public long getAmount() {
        return amount;
    }

    public void setAmount(long amount) {
        this.amount = amount;
    }

    public String getEvent() {
        return event == null ? "" : event;
    }

    public void setEvent(String event) {
        this.event = event;
    }

    public String getPackageId() {
        return packageId == null ? "" : packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }
}
