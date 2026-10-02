package org.hyzionstudios.mysticquests.narrative.media;

import javax.annotation.Nullable;

/**
 * Asks the engine whether an audio asset is loaded, so content can be checked before it plays. A
 * missing asset is a warning, not an error: asset packs can load in a different order on a
 * development server, and the runtime falls back to subtitles.
 */
public interface MediaCatalog {
    /** @return whether a SoundEvent with this id is loaded; null when the engine cannot tell */
    @Nullable
    Boolean soundExists(String soundEvent);

    /** @return whether a MusicContainer with this id is loaded; null when the engine cannot tell */
    @Nullable
    Boolean musicExists(String musicContainer);

    /** Checks nothing; used when no engine is bound. */
    MediaCatalog UNKNOWN = new MediaCatalog() {
        @Override
        public Boolean soundExists(String soundEvent) {
            return null;
        }

        @Override
        public Boolean musicExists(String musicContainer) {
            return null;
        }
    };
}
