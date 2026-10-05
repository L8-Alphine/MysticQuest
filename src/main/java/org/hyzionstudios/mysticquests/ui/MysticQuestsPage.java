package org.hyzionstudios.mysticquests.ui;

import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Ref;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.logger.HytaleLogger;
import com.hypixel.hytale.protocol.packets.interface_.CustomPageLifetime;
import com.hypixel.hytale.server.core.entity.entities.player.pages.InteractiveCustomUIPage;
import com.hypixel.hytale.server.core.ui.builder.UICommandBuilder;
import com.hypixel.hytale.server.core.ui.builder.UIEventBuilder;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;

import javax.annotation.Nonnull;
import java.util.logging.Level;

/**
 * Shared lifecycle for the MysticQuests pages, shaped by three client rules that each made a page
 * look dead when broken:
 *
 * <ul>
 *   <li><b>Every click is answered.</b> A binding locks the client's interface until a page packet
 *       arrives, and the page manager drops further input while an update is unacknowledged. A
 *       handler that returns without sending anything leaves the page frozen. So every event, even
 *       one that fails, ends in {@link #refresh()} unless the page closed.</li>
 *   <li><b>An event that cannot be decoded is still answered.</b> Decoding happens before
 *       {@link #handle}; a payload the codec rejects would otherwise throw past the page and freeze
 *       it the same way.</li>
 *   <li><b>Every update re-sends every binding.</b> An update replaces the client's binding set, so
 *       a control bound only when the page was built stops responding after the first click.
 *       {@link #render} writes values, lists and bindings together, and runs on build and after
 *       every event.</li>
 * </ul>
 *
 * <p>The page document itself is appended once, in {@link #build}; later renders only set values and
 * clear and refill lists, so scroll positions and focused fields survive a click.
 */
public abstract class MysticQuestsPage<T> extends InteractiveCustomUIPage<T> {
    private static final HytaleLogger LOGGER = HytaleLogger.forEnclosingClass();

    private boolean closed;

    protected MysticQuestsPage(PlayerRef playerRef, BuilderCodec<T> codec) {
        super(playerRef, CustomPageLifetime.CanDismissOrCloseThroughInteraction, codec);
    }

    /** The page document, relative to {@code Common/UI/Custom/}. */
    protected abstract String document();

    /** Writes every value, list and event binding the page shows. */
    protected abstract void render(UICommandBuilder commands, UIEventBuilder events);

    /** Applies one event. The page re-renders afterwards whatever happens here. */
    protected abstract void handle(Ref<EntityStore> ref, Store<EntityStore> store, T data);

    /** Called when {@link #handle} throws, so the page can say so instead of failing silently. */
    protected void onFailure(RuntimeException failure) {
    }

    @Override
    public final void build(@Nonnull Ref<EntityStore> ref, @Nonnull UICommandBuilder commands,
                            @Nonnull UIEventBuilder events, @Nonnull Store<EntityStore> store) {
        commands.append(document());
        renderSafely(commands, events);
    }

    @Override
    public void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, String rawData) {
        try {
            super.handleDataEvent(ref, store, rawData);
        } catch (RuntimeException unreadable) {
            LOGGER.at(Level.WARNING).withCause(unreadable)
                    .log("Could not read an event for " + getClass().getSimpleName() + "; answering with a refresh.");
            refresh();
        }
    }

    @Override
    public final void handleDataEvent(@Nonnull Ref<EntityStore> ref, @Nonnull Store<EntityStore> store, @Nonnull T data) {
        try {
            handle(ref, store, data);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log(getClass().getSimpleName() + " failed to handle an event.");
            onFailure(failure);
        }
        if (!closed) {
            refresh();
        }
    }

    /** Sends the current state as an update, keeping the document and its scroll positions. */
    protected void refresh() {
        UICommandBuilder commands = new UICommandBuilder();
        UIEventBuilder events = new UIEventBuilder();
        renderSafely(commands, events);
        sendUpdate(commands, events, false);
    }

    /** Closes the page; the close itself answers the click, so no refresh follows. */
    protected void closePage() {
        closed = true;
        close();
    }

    /** Something else closed the page (a service ending its scene); send nothing more to it. */
    protected void markClosed() {
        closed = true;
    }

    private void renderSafely(UICommandBuilder commands, UIEventBuilder events) {
        try {
            render(commands, events);
        } catch (RuntimeException failure) {
            LOGGER.at(Level.WARNING).withCause(failure).log(getClass().getSimpleName() + " failed to render.");
        }
    }
}
