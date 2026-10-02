package org.hyzionstudios.mysticquests.integration.triggervolumes;

import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerContext;
import com.hypixel.hytale.builtin.triggervolumes.effect.TriggerEffect;
import com.hypixel.hytale.codec.builder.BuilderCodec;

import java.util.Map;

/** Runs a placeholder-aware command as the triggering player or the server console. */
public final class MysticRunCommandEffect extends TriggerEffect {
    public static final BuilderCodec<MysticRunCommandEffect> CODEC = MysticTriggerCodecs.runCommandEffect();

    private String command = "";
    private ExecuteAs executeAs = ExecuteAs.player;
    private String packageId = "";

    public String getCommand() {
        return command == null ? "" : command;
    }

    public void setCommand(String command) {
        this.command = command;
    }

    public ExecuteAs getExecuteAs() {
        return executeAs == null ? ExecuteAs.player : executeAs;
    }

    public void setExecuteAs(ExecuteAs executeAs) {
        this.executeAs = executeAs;
    }

    public String getPackageId() {
        return packageId == null ? "" : packageId;
    }

    public void setPackageId(String packageId) {
        this.packageId = packageId;
    }

    @Override
    public void execute(TriggerContext context) {
        MysticTriggerBridge.runCommand(context, this);
    }

    public enum ExecuteAs {
        player,
        console;

        public static final Map<ExecuteAs, String> DOCUMENT_KEYS = Map.of(
                player, "player",
                console, "console");
    }
}
