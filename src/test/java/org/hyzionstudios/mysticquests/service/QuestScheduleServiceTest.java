package org.hyzionstudios.mysticquests.service;

import org.junit.jupiter.api.Test;

import java.time.ZonedDateTime;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

class QuestScheduleServiceTest {
    @Test
    void cronSupportsAliasesRangesListsAndSteps() {
        var cron = QuestScheduleService.CronExpression.parse("*/15 8-10 * * 1,3,5");
        assertTrue(cron.matches(ZonedDateTime.parse("2026-08-12T09:30:00-04:00")));
        assertFalse(cron.matches(ZonedDateTime.parse("2026-08-12T09:31:00-04:00")));
        assertFalse(cron.matches(ZonedDateTime.parse("2026-08-13T09:30:00-04:00")));
        assertTrue(QuestScheduleService.CronExpression.parse("@daily")
                .matches(ZonedDateTime.parse("2026-08-13T00:00:00-04:00")));
    }
}
