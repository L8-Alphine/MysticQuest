package org.hyzionstudios.mysticquests;

import com.hypixel.hytale.server.core.plugin.JavaPlugin;
import com.hypixel.hytale.server.core.plugin.JavaPluginInit;

import java.util.logging.Level;
import javax.annotation.Nonnull;

public class MysticquestsPlugin extends JavaPlugin {
    private MysticQuestsRuntime runtime;

    public MysticquestsPlugin(@Nonnull JavaPluginInit init) {
        super(init);
    }

    @Override
    protected void start() {
        runtime = new MysticQuestsRuntime(this);
        runtime.start();
        getLogger().at(Level.INFO).log("MysticQuests started!");
    }

    @Override
    protected void shutdown() {
        if (runtime != null) {
            runtime.close();
            runtime = null;
        }
        getLogger().at(Level.INFO).log("MysticQuests shut down.");
    }
}
