package org.hyzionstudios.mysticquests.narrative.puzzle;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.List;
import java.util.SplittableRandom;

/**
 * Chooses which candidate inputs count for one audience, deterministically.
 *
 * <p>The seed comes from stable identifiers: puzzle id, session id, and round. The same session and
 * round always choose the same inputs, which helps reproduce a report. The choice is still
 * <em>persisted</em> once made (see {@link PuzzleState}), and that stored copy is authoritative. The
 * seed is for debugging, not for re-deriving the selection.
 *
 * <p>The draw is a partial Fisher–Yates shuffle, so every subset of size {@code k} is equally likely.
 * The result keeps the authored candidate order, so debug output lists keys the way the author laid
 * them out.
 */
public final class PuzzleSelector {
    private PuzzleSelector() {
    }

    public static long seed(String puzzleId, String sessionId, int generation) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((puzzleId + "\u0000" + sessionId + "\u0000" + generation).getBytes(StandardCharsets.UTF_8));
            long seed = 0L;
            for (int index = 0; index < Long.BYTES; index++) {
                seed = (seed << 8) | (digest[index] & 0xFFL);
            }
            return seed;
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java platform", impossible);
        }
    }

    /** Picks {@code count} of {@code candidates}; all of them when {@code count} is at least their number. */
    public static List<String> select(List<String> candidates, int count, long seed) {
        if (count >= candidates.size()) {
            return List.copyOf(candidates);
        }
        List<Integer> indexes = new ArrayList<>(candidates.size());
        for (int index = 0; index < candidates.size(); index++) {
            indexes.add(index);
        }
        SplittableRandom random = new SplittableRandom(seed);
        for (int index = 0; index < count; index++) {
            int swap = index + random.nextInt(indexes.size() - index);
            Integer chosen = indexes.get(swap);
            indexes.set(swap, indexes.get(index));
            indexes.set(index, chosen);
        }
        List<Integer> chosen = new ArrayList<>(indexes.subList(0, count));
        chosen.sort(Integer::compare);
        List<String> selected = new ArrayList<>(count);
        for (int index : chosen) {
            selected.add(candidates.get(index));
        }
        return List.copyOf(selected);
    }
}
