package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.codec.codecs.EnumCodec;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertInstanceOf;

class MysticTriggerDropdownCodecTest {
    @Test
    void fixedChoiceFieldsExposeEnumSchemasForEditorDropdowns() {
        assertArrayEquals(
                new String[] {"player", "global", "entity", "block", "volume"},
                enumCodec(MysticAddTagEffect.CODEC, "Scope").getEnumKeys());
        assertArrayEquals(
                new String[] {"eq", "ne", "gt", "gte", "lt", "lte", "exists"},
                enumCodec(MysticVariableCondition.CODEC, "Operator").getEnumKeys());
        assertArrayEquals(
                new String[] {"player", "global"},
                enumCodec(MysticRichMessageEffect.CODEC, "Audience").getEnumKeys());
        assertArrayEquals(
                new String[] {"player", "console"},
                enumCodec(MysticRunCommandEffect.CODEC, "ExecuteAs").getEnumKeys());
    }

    @Test
    void dropdownAdaptersKeepExistingLowerCaseTriggerValues() {
        MysticAddTagEffect effect = new MysticAddTagEffect();
        effect.setScope("server");
        assertEquals(MysticTriggerScope.global, effect.getScopeOption());
        effect.setScopeOption(MysticTriggerScope.volume);
        assertEquals("volume", effect.getScope());

        MysticVariableCondition condition = new MysticVariableCondition();
        condition.setOperator(">=");
        assertEquals(MysticTriggerCondition.VariableOperator.gte, condition.getOperatorOption());
        condition.setOperatorOption(MysticTriggerCondition.VariableOperator.ne);
        assertEquals("ne", condition.getOperator());

        MysticRunCommandEffect command = new MysticRunCommandEffect();
        assertEquals(MysticRunCommandEffect.ExecuteAs.player, command.getExecuteAs());
    }

    private static EnumCodec<?> enumCodec(BuilderCodec<?> owner, String field) {
        Codec<?> codec = owner.getEntries().get(field).get(0).getCodec().getChildCodec();
        return assertInstanceOf(EnumCodec.class, codec);
    }
}
