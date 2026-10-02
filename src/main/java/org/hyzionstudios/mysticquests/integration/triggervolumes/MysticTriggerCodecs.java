package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerCondition;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;

import java.util.Map;
import java.util.function.Supplier;

final class MysticTriggerCodecs {
    private MysticTriggerCodecs() {
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> tagEffect(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Tag"), MysticTriggerEffect::setTag, MysticTriggerEffect::getTag)
                .build();
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> setVariableEffect(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(string("Value"), MysticTriggerEffect::setValue, MysticTriggerEffect::getValue)
                .build();
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> removeVariableEffect(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .build();
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> incrementVariableEffect(
            Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(longCodec("Amount"), MysticTriggerEffect::setAmount, MysticTriggerEffect::getAmount)
                .build();
    }

    static <T extends MysticTriggerEffect> BuilderCodec<T> eventEffect(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Event"), MysticTriggerEffect::setEvent, MysticTriggerEffect::getEvent)
                .addField(string("Tag"), MysticTriggerEffect::setTag, MysticTriggerEffect::getTag)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(string("Value"), MysticTriggerEffect::setValue, MysticTriggerEffect::getValue)
                .addField(longCodec("Amount"), MysticTriggerEffect::setAmount, MysticTriggerEffect::getAmount)
                .addField(string("Package"), MysticTriggerEffect::setPackageId, MysticTriggerEffect::getPackageId)
                .build();
    }

    /**
     * Codec for the generic action effect: the shared state fields plus the ones only the packet and
     * visibility actions use, so one volume effect can drive every event type.
     */
    static <T extends MysticActionEffect> BuilderCodec<T> action(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerEffect.BASE_CODEC)
                .addField(string("Action"), MysticActionEffect::setAction, MysticActionEffect::getAction)
                .addField(scope(), MysticTriggerEffect::setScopeOption, MysticTriggerEffect::getScopeOption)
                .addField(string("Target"), MysticTriggerEffect::setTarget, MysticTriggerEffect::getTarget)
                .addField(string("Viewer"), MysticActionEffect::setViewer, MysticActionEffect::getViewer)
                .addField(string("Tag"), MysticTriggerEffect::setTag, MysticTriggerEffect::getTag)
                .addField(string("Key"), MysticTriggerEffect::setKey, MysticTriggerEffect::getKey)
                .addField(string("Value"), MysticTriggerEffect::setValue, MysticTriggerEffect::getValue)
                .addField(longCodec("Amount"), MysticTriggerEffect::setAmount, MysticTriggerEffect::getAmount)
                .addField(string("Mode"), MysticActionEffect::setMode, MysticActionEffect::getMode)
                .addField(string("Message"), MysticActionEffect::setMessage, MysticActionEffect::getMessage)
                .addField(string("Subtitle"), MysticActionEffect::setSubtitle, MysticActionEffect::getSubtitle)
                .addField(booleanCodec("Locked"), MysticActionEffect::setLocked, MysticActionEffect::getLocked)
                .addField(string("Package"), MysticTriggerEffect::setPackageId, MysticTriggerEffect::getPackageId)
                .build();
    }

    static <T extends MysticTriggerCondition> BuilderCodec<T> tagCondition(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerCondition.BASE_CODEC)
                .addField(scope(), MysticTriggerCondition::setScopeOption, MysticTriggerCondition::getScopeOption)
                .addField(string("Target"), MysticTriggerCondition::setTarget, MysticTriggerCondition::getTarget)
                .addField(string("Tag"), MysticTriggerCondition::setTag, MysticTriggerCondition::getTag)
                .addField(booleanCodec("Invert"), MysticTriggerCondition::setInvert, MysticTriggerCondition::getInvert)
                .build();
    }

    static <T extends MysticTriggerCondition> BuilderCodec<T> variableCondition(Class<T> type, Supplier<T> supplier) {
        return BuilderCodec.builder(type, supplier, TriggerCondition.BASE_CODEC)
                .addField(scope(), MysticTriggerCondition::setScopeOption, MysticTriggerCondition::getScopeOption)
                .addField(string("Target"), MysticTriggerCondition::setTarget, MysticTriggerCondition::getTarget)
                .addField(string("Key"), MysticTriggerCondition::setKey, MysticTriggerCondition::getKey)
                .addField(string("Value"), MysticTriggerCondition::setValue, MysticTriggerCondition::getValue)
                .addField(
                        enumCodec(
                                "Operator",
                                MysticTriggerCondition.VariableOperator.class,
                                MysticTriggerCondition.VariableOperator.ALIASES),
                        MysticTriggerCondition::setOperatorOption,
                        MysticTriggerCondition::getOperatorOption)
                .addField(booleanCodec("Invert"), MysticTriggerCondition::setInvert, MysticTriggerCondition::getInvert)
                .build();
    }

    static BuilderCodec<MysticRichMessageEffect> richMessageEffect() {
        return BuilderCodec.builder(
                        MysticRichMessageEffect.class, MysticRichMessageEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Message"), MysticRichMessageEffect::setMessage, MysticRichMessageEffect::getMessage)
                .addField(
                        enumCodec(
                                "Audience",
                                MysticRichMessageEffect.Audience.class,
                                MysticRichMessageEffect.Audience.ALIASES),
                        MysticRichMessageEffect::setAudience,
                        MysticRichMessageEffect::getAudience)
                .addField(string("Package"), MysticRichMessageEffect::setPackageId, MysticRichMessageEffect::getPackageId)
                .build();
    }

    static BuilderCodec<MysticRunCommandEffect> runCommandEffect() {
        return BuilderCodec.builder(
                        MysticRunCommandEffect.class, MysticRunCommandEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Command"), MysticRunCommandEffect::setCommand, MysticRunCommandEffect::getCommand)
                .addField(
                        enumCodec(
                                "ExecuteAs",
                                MysticRunCommandEffect.ExecuteAs.class,
                                MysticRunCommandEffect.ExecuteAs.DOCUMENT_KEYS),
                        MysticRunCommandEffect::setExecuteAs,
                        MysticRunCommandEffect::getExecuteAs)
                .addField(string("Package"), MysticRunCommandEffect::setPackageId, MysticRunCommandEffect::getPackageId)
                .build();
    }

    static BuilderCodec<MysticTriggerEnabledCondition> triggerEnabledCondition() {
        return BuilderCodec.builder(
                        MysticTriggerEnabledCondition.class, MysticTriggerEnabledCondition::new, TriggerCondition.BASE_CODEC)
                .addField(string("Volume"), MysticTriggerEnabledCondition::setVolume, MysticTriggerEnabledCondition::getVolume)
                .addField(booleanCodec("Invert"), MysticTriggerEnabledCondition::setInvert, MysticTriggerEnabledCondition::getInvert)
                .build();
    }

    static BuilderCodec<MysticPuzzleInputAvailableCondition> puzzleInputCondition() {
        return BuilderCodec.builder(
                        MysticPuzzleInputAvailableCondition.class, MysticPuzzleInputAvailableCondition::new,
                        TriggerCondition.BASE_CODEC)
                .addField(string("Puzzle"), MysticPuzzleInputAvailableCondition::setPuzzle, MysticPuzzleInputAvailableCondition::getPuzzle)
                .addField(string("Input"), MysticPuzzleInputAvailableCondition::setInput, MysticPuzzleInputAvailableCondition::getInput)
                .addField(booleanCodec("Invert"), MysticPuzzleInputAvailableCondition::setInvert, MysticPuzzleInputAvailableCondition::getInvert)
                .build();
    }

    static BuilderCodec<MysticPuzzleInputEffect> puzzleInputEffect() {
        return BuilderCodec.builder(MysticPuzzleInputEffect.class, MysticPuzzleInputEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Puzzle"), MysticPuzzleInputEffect::setPuzzle, MysticPuzzleInputEffect::getPuzzle)
                .addField(string("Input"), MysticPuzzleInputEffect::setInput, MysticPuzzleInputEffect::getInput)
                .addField(booleanCodec("Release"), MysticPuzzleInputEffect::setRelease, MysticPuzzleInputEffect::getRelease)
                .build();
    }

    static BuilderCodec<MysticPuzzleResetEffect> puzzleResetEffect() {
        return BuilderCodec.builder(MysticPuzzleResetEffect.class, MysticPuzzleResetEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Puzzle"), MysticPuzzleResetEffect::setPuzzle, MysticPuzzleResetEffect::getPuzzle)
                .addField(booleanCodec("Reroll"), MysticPuzzleResetEffect::setReroll, MysticPuzzleResetEffect::getReroll)
                .build();
    }

    static BuilderCodec<MysticCutscenePlayEffect> cutscenePlayEffect() {
        return BuilderCodec.builder(MysticCutscenePlayEffect.class, MysticCutscenePlayEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Cutscene"), MysticCutscenePlayEffect::setCutscene, MysticCutscenePlayEffect::getCutscene)
                .build();
    }

    static BuilderCodec<MysticTriggerStateEffect> triggerStateEffect() {
        return BuilderCodec.builder(MysticTriggerStateEffect.class, MysticTriggerStateEffect::new, TriggerEffect.BASE_CODEC)
                .addField(string("Volume"), MysticTriggerStateEffect::setVolume, MysticTriggerStateEffect::getVolume)
                .addField(
                        enumCodec("State", MysticTriggerStateEffect.State.class, MysticTriggerStateEffect.State.DOCUMENT_KEYS),
                        MysticTriggerStateEffect::setState,
                        MysticTriggerStateEffect::getState)
                .addField(
                        enumCodec("Scope", MysticTriggerStateEffect.Scope.class, MysticTriggerStateEffect.Scope.DOCUMENT_KEYS),
                        MysticTriggerStateEffect::setScope,
                        MysticTriggerStateEffect::getScope)
                .build();
    }

    private static KeyedCodec<MysticTriggerScope> scope() {
        return enumCodec("Scope", MysticTriggerScope.class, MysticTriggerScope.DOCUMENT_KEYS);
    }

    private static <T extends Enum<T>> KeyedCodec<T> enumCodec(
            String key, Class<T> type, Map<T, String> aliases) {
        EnumCodec<T> codec = new EnumCodec<>(type);
        aliases.forEach(codec::documentKey);
        return new KeyedCodec<>(key, codec, false);
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
