package org.hyzionstudios.mysticquests.narrative.session;

import com.fasterxml.jackson.databind.node.ObjectNode;

/**
 * A subsystem's own state stored inside a session: a puzzle's selection and progress, and later
 * dialogue position, media state and cutscene progress.
 *
 * <p>Components let each subsystem own its format without the session knowing about it, and they
 * travel with the session on save, transfer, fork and checkpoint. A subsystem that mutates its
 * component must call {@link QuestSession#markChanged()} so the change is saved.
 */
public interface SessionComponent {
    ObjectNode toJson();
}
