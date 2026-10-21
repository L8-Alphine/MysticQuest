package org.hyzionstudios.mysticquests.ui;

/**
 * How the dialogue's live model preview frames any NPC, worked out from its bounding box alone.
 *
 * <p>The client frames a preview that holds entities only by the union of their positions, and the
 * NPC's own position is its feet. So the preview also carries two specks of the model, scaled to
 * nothing, at opposite corners of a box; the camera fits that box, and the box's size relative to
 * the model decides how much of the card the model fills. The model itself is sent at its real
 * scale, so nothing about it (attachments, particles) has to be rescaled.
 *
 * <p>The reference fit comes from EndgameQoL's {@code PetShowcase}, which shows a model on the same
 * component: a box of half-extents {@value #REFERENCE_HALF_WIDTH} x {@value #REFERENCE_HALF_HEIGHT}
 * holds a model up to {@value #REFERENCE_FIT_WIDTH} blocks across while it turns and
 * {@value #REFERENCE_FIT_HEIGHT} blocks tall, on a roughly square stage. The dialogue card is about
 * half as wide as it is tall ({@link #CARD_ASPECT}), so it holds half that width. The box grows with
 * whichever of the NPC's height and turning width runs out of room first.
 */
final class SpeakerFraming {
    static final float REFERENCE_HALF_WIDTH = 2.5f;
    static final float REFERENCE_HALF_HEIGHT = 1.6f;
    static final float REFERENCE_FIT_WIDTH = 10f;
    static final float REFERENCE_FIT_HEIGHT = 8.5f;
    /** Inner width over inner height of {@code #SpeakerStage} in ConversationPage.ui (160 x 324). */
    static final float CARD_ASPECT = 160f / 324f;
    /** The smallest box, so a mouse-sized model does not put the camera inside it. */
    static final float MIN_FACTOR = 0.04f;

    /** Half-extents of the framing box and the height its centre sits at, in blocks. */
    record Frame(float halfWidth, float halfHeight, float midHeight) {
    }

    private SpeakerFraming() {
    }

    /** The frame for a model whose bounding box has these dimensions and vertical middle. */
    static Frame fit(double width, double height, double depth, double middleY) {
        double turningWidth = Math.hypot(sane(width), sane(depth));
        double factor = Math.max(
                sane(height) / REFERENCE_FIT_HEIGHT,
                turningWidth / (REFERENCE_FIT_WIDTH * CARD_ASPECT));
        if (!(factor >= MIN_FACTOR)) {
            factor = MIN_FACTOR;
        }
        double mid = Double.isFinite(middleY) ? middleY : sane(height) / 2;
        return new Frame(
                (float) (REFERENCE_HALF_WIDTH * factor),
                (float) (REFERENCE_HALF_HEIGHT * factor),
                (float) mid);
    }

    private static double sane(double extent) {
        return Double.isFinite(extent) && extent > 0 ? extent : 0;
    }
}
