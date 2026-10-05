package org.hyzionstudios.mysticquests.studio;

/** A valid test clip for tests outside this package. */
public final class StudioAudioTestAccess {
    private StudioAudioTestAccess() {
    }

    public static byte[] vorbis() {
        return StudioAudioTest.vorbis(1, 48000, 1.5);
    }
}
