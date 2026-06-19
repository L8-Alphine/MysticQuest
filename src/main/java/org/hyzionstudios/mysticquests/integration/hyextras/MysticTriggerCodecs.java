package org.hyzionstudios.mysticquests.integration.hyextras;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.function.Supplier;

final class MysticTriggerCodecs {
    private MysticTriggerCodecs() {
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> effect(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(string("Scope"), MysticTriggerEffect::setScope, MysticTriggerEffect::getScope)
                .addField(string("scope"), MysticTriggerEffect::setScope, MysticTriggerEffect::getScope)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Tag"), MysticTriggerEffect::setTag, MysticTriggerEffect::getTag)
                .addField(string("tag"), MysticTriggerEffect::setTag, MysticTriggerEffect::getTag)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(string("key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(string("Value"), MysticTriggerEffect::setValue, MysticTriggerEffect::getValue)
                .addField(string("value"), MysticTriggerEffect::setValue, MysticTriggerEffect::getValue)
                .addField(longCodec("Amount"), MysticTriggerEffect::setAmount, MysticTriggerEffect::getAmount)
                .addField(longCodec("amount"), MysticTriggerEffect::setAmount, MysticTriggerEffect::getAmount)
                .addField(string("Event"), MysticTriggerEffect::setEvent, MysticTriggerEffect::getEvent)
                .addField(string("event"), MysticTriggerEffect::setEvent, MysticTriggerEffect::getEvent)
                .addField(string("Package"), MysticTriggerEffect::setPackageId, MysticTriggerEffect::getPackageId)
                .addField(string("package"), MysticTriggerEffect::setPackageId, MysticTriggerEffect::getPackageId)
                .build();
    }

    static <T extends MysticTriggerCondition> BuilderCodec<T> condition(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerCondition.BASE_CODEC)
                .addField(string("Scope"), MysticTriggerCondition::setScope, MysticTriggerCondition::getScope)
                .addField(string("scope"), MysticTriggerCondition::setScope, MysticTriggerCondition::getScope)
                .addField(string("Target"), MysticTriggerCondition::setTarget, MysticTriggerCondition::getTarget)
                .addField(string("target"), MysticTriggerCondition::setTarget, MysticTriggerCondition::getTarget)
                .addField(string("Tag"), MysticTriggerCondition::setTag, MysticTriggerCondition::getTag)
                .addField(string("tag"), MysticTriggerCondition::setTag, MysticTriggerCondition::getTag)
                .addField(string("Key"), MysticTriggerCondition::setKey, MysticTriggerCondition::getKey)
                .addField(string("key"), MysticTriggerCondition::setKey, MysticTriggerCondition::getKey)
                .addField(string("Value"), MysticTriggerCondition::setValue, MysticTriggerCondition::getValue)
                .addField(string("value"), MysticTriggerCondition::setValue, MysticTriggerCondition::getValue)
                .addField(string("Operator"), MysticTriggerCondition::setOperator, MysticTriggerCondition::getOperator)
                .addField(string("operator"), MysticTriggerCondition::setOperator, MysticTriggerCondition::getOperator)
                .addField(booleanCodec("Invert"), MysticTriggerCondition::setInvert, MysticTriggerCondition::getInvert)
                .addField(booleanCodec("invert"), MysticTriggerCondition::setInvert, MysticTriggerCondition::getInvert)
                .build();
    }

    private static KeyedCodec<String> string(String key) {
        return new KeyedCodec<>(key, Codec.STRING, false);
    }

    private static KeyedCodec<Long> longCodec(String key) {
        return new KeyedCodec<>(key, Codec.LONG, false);
    }

    private static KeyedCodec<Boolean> booleanCodec(String key) {
        return new KeyedCodec<>(key, Codec.BOOLEAN, false);
    }
}
