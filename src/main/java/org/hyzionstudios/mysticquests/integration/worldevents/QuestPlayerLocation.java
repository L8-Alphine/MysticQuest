package org.hyzionstudios.mysticquests.integration.worldevents;

import com.hypixel.hytale.builtin.adventure.worldevents.condition.location.EventLocation;
import com.hypixel.hytale.builtin.adventure.worldevents.util.Vector3iList;
import com.hypixel.hytale.codec.Codec;
import com.hypixel.hytale.codec.KeyedCodec;
import com.hypixel.hytale.codec.builder.BuilderCodec;
import com.hypixel.hytale.component.Store;
import com.hypixel.hytale.component.query.Query;
import com.hypixel.hytale.server.core.entity.entities.Player;
import com.hypixel.hytale.server.core.modules.entity.component.Spectating;
import com.hypixel.hytale.server.core.modules.entity.component.TransformComponent;
import com.hypixel.hytale.server.core.universe.PlayerRef;
import com.hypixel.hytale.server.core.universe.world.storage.EntityStore;
import org.joml.Vector3d;

import javax.annotation.Nonnull;
import javax.annotation.Nullable;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ThreadLocalRandom;

/**
 * A world-event location type, {@code "Type": "MysticQuestsPlayerLocation"}: candidate spots in a ring
 * around the player whose quest started the event.
 *
 * <p>Vanilla's {@code WildernessLocation} places a breach near a random player in the world
 * ({@code WildernessLocation#collectWilderness}), which on a busy server is rarely the player whose quest
 * asked for it. A quest that starts an event through {@code startWorldEvent} names its player as the
 * world's anchor first ({@link #anchor}); an event whose {@code LocationCondition} uses this type then
 * searches around that player. With no recent anchor in the world it falls back to a random player,
 * as the vanilla type does.
 *
 * <p>Like every {@link EventLocation} it only proposes positions: the {@code LocationCondition} fits them
 * to the terrain and checks the prefab spawners' masks, and retries when none pass.
 */
public final class QuestPlayerLocation implements EventLocation {
    public static final String ID = "MysticQuestsPlayerLocation";

    /** How long a quest's request to place an event near its player is honoured. */
    private static final long ANCHOR_TTL_MILLIS = 180_000L;
    private static final int CANDIDATES = 48;
    private static final Map<String, Anchor> ANCHORS = new ConcurrentHashMap<>();
    private static final Query<EntityStore> PLAYERS = Query.and(
            Player.getComponentType(),
            PlayerRef.getComponentType(),
            TransformComponent.getComponentType(),
            Query.not(Spectating.getComponentType()));

    private Config config = Config.DEFAULT;

    /** The next event placed in {@code world} with this location type goes near {@code player}. */
    public static void anchor(String world, UUID player) {
        ANCHORS.put(world, new Anchor(player, System.currentTimeMillis()));
    }

    @Nonnull
    @Override
    public CompletableFuture<Vector3iList> find(@Nonnull Store<EntityStore> store) {
        Vector3iList candidates = new Vector3iList();
        Vector3d origin = origin(store);
        if (origin == null) {
            return CompletableFuture.completedFuture(candidates);
        }
        ThreadLocalRandom random = ThreadLocalRandom.current();
        for (int i = 0; i < CANDIDATES; i++) {
            double angle = random.nextDouble(Math.PI * 2);
            double distance = config.minHorizontalDistance
                    + random.nextDouble() * (config.maxHorizontalDistance - config.minHorizontalDistance);
            int vertical = config.minVerticalDistance
                    + random.nextInt(config.maxVerticalDistance - config.minVerticalDistance + 1);
            candidates.add(
                    (int) Math.floor(origin.x + Math.cos(angle) * distance),
                    (int) Math.floor(origin.y) + (random.nextBoolean() ? vertical : -vertical),
                    (int) Math.floor(origin.z + Math.sin(angle) * distance));
        }
        return CompletableFuture.completedFuture(candidates);
    }

    /** The anchored player when they are in this world, else a random player in it. */
    @Nullable
    private static Vector3d origin(Store<EntityStore> store) {
        String world = store.getExternalData().getWorld().getName();
        Anchor anchor = ANCHORS.get(world);
        UUID wanted = anchor != null && System.currentTimeMillis() - anchor.at() <= ANCHOR_TTL_MILLIS ? anchor.player() : null;
        List<Vector3d> everyone = new ArrayList<>();
        Vector3d[] anchored = new Vector3d[1];
        store.forEachChunk(PLAYERS, (chunk, buffer) -> {
            for (int i = 0; i < chunk.size(); i++) {
                PlayerRef player = chunk.getComponent(i, PlayerRef.getComponentType());
                TransformComponent transform = chunk.getComponent(i, TransformComponent.getComponentType());
                if (player == null || transform == null || transform.getPosition() == null) {
                    continue;
                }
                Vector3d at = new Vector3d(transform.getPosition());
                everyone.add(at);
                if (player.getUuid().equals(wanted)) {
                    anchored[0] = at;
                }
            }
        });
        if (anchored[0] != null) {
            return anchored[0];
        }
        return everyone.isEmpty() ? null : everyone.get(ThreadLocalRandom.current().nextInt(everyone.size()));
    }

    private record Anchor(UUID player, long at) {
    }

    public static final class Config implements EventLocation.Config {
        public static final BuilderCodec<Config> CODEC = BuilderCodec.builder(Config.class, Config::new)
                .documentation("Finds positions around the player whose MysticQuests quest started the event, "
                        + "or around a random player when no quest asked.")
                .append(new KeyedCodec<>("MinHorizontalDistance", Codec.INTEGER),
                        (self, value) -> self.minHorizontalDistance = value,
                        self -> self.minHorizontalDistance)
                .documentation("Minimum horizontal distance from the player in blocks.")
                .add()
                .append(new KeyedCodec<>("MaxHorizontalDistance", Codec.INTEGER),
                        (self, value) -> self.maxHorizontalDistance = value,
                        self -> self.maxHorizontalDistance)
                .documentation("Maximum horizontal distance from the player in blocks.")
                .add()
                .append(new KeyedCodec<>("MinVerticalDistance", Codec.INTEGER),
                        (self, value) -> self.minVerticalDistance = value,
                        self -> self.minVerticalDistance)
                .documentation("Minimum vertical distance from the player in blocks.")
                .add()
                .append(new KeyedCodec<>("MaxVerticalDistance", Codec.INTEGER),
                        (self, value) -> self.maxVerticalDistance = value,
                        self -> self.maxVerticalDistance)
                .documentation("Maximum vertical distance from the player in blocks.")
                .add()
                .afterDecode(Config::afterDecode)
                .build();

        static final Config DEFAULT = new Config();

        private int minHorizontalDistance = 30;
        private int maxHorizontalDistance = 60;
        private int minVerticalDistance = 0;
        private int maxVerticalDistance = 16;

        @Nonnull
        @Override
        public EventLocation create() {
            QuestPlayerLocation location = new QuestPlayerLocation();
            location.config = this;
            return location;
        }

        private void afterDecode() {
            minHorizontalDistance = Math.max(0, minHorizontalDistance);
            maxHorizontalDistance = Math.max(minHorizontalDistance, maxHorizontalDistance);
            minVerticalDistance = Math.abs(minVerticalDistance);
            maxVerticalDistance = Math.max(minVerticalDistance, Math.abs(maxVerticalDistance));
        }
    }
}
