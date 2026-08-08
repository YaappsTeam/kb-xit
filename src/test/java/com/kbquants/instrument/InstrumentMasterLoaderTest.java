package com.kbquants.instrument;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Unit tests for InstrumentMasterLoader's weekly refresh schedule.
 * <p>
 * The download itself is not exercised here (it needs network access);
 * what matters is that the cache stamp only moves once a week, since that
 * is what keeps the download and 37 MB parse off the VM on the other six
 * days.
 */
class InstrumentMasterLoaderTest {

    // 2026-08-05 is a Wednesday.
    private static final LocalDate WEDNESDAY = LocalDate.of(2026, 8, 5);

    @Test
    void wednesdayShouldAnchorToItself() {
        assertEquals(WEDNESDAY, InstrumentMasterLoader.refreshAnchor(WEDNESDAY));
    }

    @Test
    void thursdayShouldAnchorToTheDayBefore() {
        assertEquals(WEDNESDAY, InstrumentMasterLoader.refreshAnchor(WEDNESDAY.plusDays(1)));
    }

    @Test
    void tuesdayShouldAnchorToThePreviousWednesday() {
        assertEquals(WEDNESDAY, InstrumentMasterLoader.refreshAnchor(WEDNESDAY.plusDays(6)));
    }

    @Test
    void nextWednesdayShouldAnchorToANewWeek() {
        assertEquals(WEDNESDAY.plusDays(7), InstrumentMasterLoader.refreshAnchor(WEDNESDAY.plusDays(7)));
    }

    /**
     * The property that actually matters: every day of a Wed-to-Tue week
     * maps to the same stamp, so exactly one download happens per week.
     */
    @Test
    void everyDayOfTheWeekShouldShareOneAnchor() {
        for (int day = 0; day < 7; day++) {
            assertEquals(WEDNESDAY, InstrumentMasterLoader.refreshAnchor(WEDNESDAY.plusDays(day)),
                    "day offset " + day);
        }
    }

    @Test
    void anchorShouldNeverBeInTheFuture() {
        for (int day = 0; day < 30; day++) {
            LocalDate date = WEDNESDAY.plusDays(day);
            assertTrue(!InstrumentMasterLoader.refreshAnchor(date).isAfter(date));
        }
    }
}
