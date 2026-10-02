package org.hyzionstudios.mysticquests.integration.triggervolumes;

import org.hyzionstudios.mysticquests.state.StateScope;

import java.util.Map;

/** Lower-case enum names preserve the existing trigger JSON values when used by EnumCodec. */
public enum MysticTriggerScope {
    player,
    global,
    entity,
    block,
    volume;

    public static final Map<MysticTriggerScope, String> DOCUMENT_KEYS = Map.of(
            player, "player",
            global, "global",
            entity, "entity",
            block, "block",
            volume, "volume");

    public static MysticTriggerScope parse(String value) {
        return valueOf(StateScope.parse(value).id());
    }
}
