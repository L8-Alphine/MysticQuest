package org.hyzionstudios.mysticquests.ui;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

final class SpeakerFramingTest {
    private static final float EPSILON = 1e-4f;

    @Test
    void theBoxKeepsTheReferenceShapeAndCentresOnTheModel() {
        SpeakerFraming.Frame frame = SpeakerFraming.fit(0.6, 1.8, 0.6, 0.9);

        assertEquals(SpeakerFraming.REFERENCE_HALF_WIDTH / SpeakerFraming.REFERENCE_HALF_HEIGHT,
                frame.halfWidth() / frame.halfHeight(), EPSILON);
        assertEquals(0.9f, frame.midHeight(), EPSILON);
    }

    @Test
    void aTallerModelGetsAProportionallyBiggerBox() {
        SpeakerFraming.Frame human = SpeakerFraming.fit(0.6, 1.8, 0.6, 0.9);
        SpeakerFraming.Frame giant = SpeakerFraming.fit(1.2, 3.6, 1.2, 1.8);

        assertEquals(2f, giant.halfHeight() / human.halfHeight(), EPSILON,
                "Same shape twice the size fills the card the same way");
    }

    @Test
    void aTallNarrowNpcIsLimitedByItsHeight() {
        double height = 1.8;
        SpeakerFraming.Frame frame = SpeakerFraming.fit(0.3, height, 0.3, 0.9);

        assertEquals(height / SpeakerFraming.REFERENCE_FIT_HEIGHT * SpeakerFraming.REFERENCE_HALF_HEIGHT,
                frame.halfHeight(), EPSILON);
    }

    @Test
    void aWideNpcIsLimitedByItsTurningWidthOnTheNarrowCard() {
        double width = 4;
        double depth = 3;
        SpeakerFraming.Frame frame = SpeakerFraming.fit(width, 1, depth, 0.5);

        double factor = Math.hypot(width, depth) / (SpeakerFraming.REFERENCE_FIT_WIDTH * SpeakerFraming.CARD_ASPECT);
        assertEquals(factor * SpeakerFraming.REFERENCE_HALF_WIDTH, frame.halfWidth(), EPSILON);
    }

    @Test
    void degenerateBoxesStillGetAUsableFrame() {
        SpeakerFraming.Frame empty = SpeakerFraming.fit(0, 0, 0, Double.NaN);
        SpeakerFraming.Frame broken = SpeakerFraming.fit(Double.NaN, Double.POSITIVE_INFINITY, -1, 0);

        assertEquals(SpeakerFraming.MIN_FACTOR * SpeakerFraming.REFERENCE_HALF_WIDTH, empty.halfWidth(), EPSILON);
        assertEquals(0f, empty.midHeight(), EPSILON);
        assertTrue(Float.isFinite(broken.halfWidth()) && broken.halfWidth() > 0);
        assertTrue(Float.isFinite(broken.halfHeight()) && broken.halfHeight() > 0);
    }
}
