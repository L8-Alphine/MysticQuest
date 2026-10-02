package org.hyzionstudios.mysticquests.narrative.media;

import org.hyzionstudios.mysticquests.narrative.value.QuestValue.EntityRefValue;

import javax.annotation.Nullable;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

/**
 * The engine side of the media system: who is online, what language they use, and how one sound or
 * subtitle reaches one listener. Everything is addressed to a single listener; the core decides who
 * hears what, so the sink never broadcasts.
 *
 * <p>Calls arrive with the media service's lock held and must not block: write a packet or hand the
 * work to a world thread.
 */
public interface MediaSink {
    /** Where a sound comes from for one listener. */
    record Placement(Spatial spatial, double x, double y, double z, @Nullable EntityRefValue entity) {
        public static final Placement HEAD = new Placement(Spatial.TWO_D, 0, 0, 0, null);

        public static Placement at(double x, double y, double z) {
            return new Placement(Spatial.POSITION, x, y, z, null);
        }

        public static Placement on(EntityRefValue entity) {
            return new Placement(Spatial.ENTITY, 0, 0, 0, entity);
        }
    }

    /** One sound for one listener. */
    record Cue(MediaAsset asset, String sound, Placement placement) {
    }

    /** One subtitle line for one listener. */
    record Subtitle(@Nullable Speaker speaker, @Nullable String text, @Nullable String key, long durationMillis) {
    }

    Collection<UUID> online();

    /** The listener's world, for world-wide audiences; null when unknown. */
    @Nullable
    String worldOf(UUID player);

    /** The listener's client language, for example {@code en-US}; null when unknown. */
    @Nullable
    String locale(UUID player);

    /** @return false when the sound could not be played, for example its asset is not loaded here */
    boolean play(UUID listener, Cue cue);

    void subtitle(UUID listener, Subtitle subtitle);

    /** No engine: nobody is online and nothing plays. Used until the server binds a real sink, and in tests. */
    MediaSink NONE = new MediaSink() {
        @Override
        public Collection<UUID> online() {
            return List.of();
        }

        @Override
        public String worldOf(UUID player) {
            return null;
        }

        @Override
        public String locale(UUID player) {
            return null;
        }

        @Override
        public boolean play(UUID listener, Cue cue) {
            return false;
        }

        @Override
        public void subtitle(UUID listener, Subtitle subtitle) {
        }
    };
}
