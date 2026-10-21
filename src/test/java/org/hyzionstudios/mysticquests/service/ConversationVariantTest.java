package org.hyzionstudios.mysticquests.service;

import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;

final class ConversationVariantTest {
    @Test
    void aReturningGreetingNeverImmediatelyRepeats() {
        for (int previous = 0; previous < 5; previous++) {
            for (int roll = 0; roll < 4; roll++) {
                int outcome = roll;
                int selected = ConversationService.selectVariantIndex(5, previous, ignored -> outcome);
                assertNotEquals(previous, selected);
            }
        }
    }

    @Test
    void theFirstGreetingCanSelectAnyVariant() {
        assertEquals(0, ConversationService.selectVariantIndex(5, -1, ignored -> 0));
        assertEquals(4, ConversationService.selectVariantIndex(5, -1, ignored -> 4));
    }
}
