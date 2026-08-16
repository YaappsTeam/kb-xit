package com.kbquants.live;

import org.junit.jupiter.api.Test;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.util.concurrent.atomic.AtomicInteger;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * The daily-wall-clock-cutoff decision, tested without waiting for a real
 * clock. Uses end-of-day-shaped values (15:15 IST) since that's the
 * original use case, but every assertion here is generic -- the same
 * class also drives the morning instrument-window refresh.
 */
class DailyWallClockScheduleTest {

    private static final ZoneId IST = ZoneId.of("Asia/Kolkata");
    private static final LocalTime CUTOFF = LocalTime.of(15, 15);
    private static final LocalDate TODAY = LocalDate.of(2026, 8, 10);

    private static DailyWallClockSchedule schedule(Runnable onCutoff) {
        return new DailyWallClockSchedule("test schedule", CUTOFF, IST, onCutoff);
    }

    @Test
    void shouldNotFireBeforeTheCutoff() {

        DailyWallClockSchedule schedule = schedule(() -> {});
        schedule.resetLastFired();

        assertFalse(schedule.shouldFire(TODAY, LocalTime.of(15, 14, 59)));
    }

    @Test
    void shouldFireAtTheCutoff() {

        DailyWallClockSchedule schedule = schedule(() -> {});
        schedule.resetLastFired();

        assertTrue(schedule.shouldFire(TODAY, CUTOFF));
    }

    @Test
    void shouldFireAfterTheCutoff() {

        DailyWallClockSchedule schedule = schedule(() -> {});
        schedule.resetLastFired();

        assertTrue(schedule.shouldFire(TODAY, LocalTime.of(15, 30)));
    }

    /** Polling every 30s must not close positions repeatedly. */
    @Test
    void shouldFireOnlyOnceADay() {

        AtomicInteger fired = new AtomicInteger();
        DailyWallClockSchedule schedule = schedule(fired::incrementAndGet);
        schedule.resetLastFired();

        schedule.checkOnce();
        schedule.checkOnce();
        schedule.checkOnce();

        assertTrue(fired.get() <= 1, "fired " + fired.get() + " times in one day");
    }

    /**
     * Starting mid-session must not immediately square off for a cutoff
     * that has already passed -- the user may have deliberately reopened
     * after it, and a restart would otherwise close the position instantly.
     */
    @Test
    void startingAfterTheCutoffShouldNotFireToday() {

        AtomicInteger fired = new AtomicInteger();
        DailyWallClockSchedule schedule = schedule(fired::incrementAndGet);

        schedule.start();
        try {
            schedule.checkOnce();
            assertEquals(0, fired.get(), "a restart after the cutoff must not close positions immediately");
        } finally {
            schedule.stop();
        }
    }

    @Test
    void shouldFireAgainOnTheNextDay() {

        DailyWallClockSchedule schedule = schedule(() -> {});
        schedule.resetLastFired();

        assertTrue(schedule.shouldFire(TODAY, CUTOFF));
        // Simulate having fired today.
        schedule.checkOnce();

        assertTrue(schedule.shouldFire(TODAY.plusDays(1), CUTOFF),
                "a new day must be eligible again");
    }

    /**
     * A scheduled task that throws is silently cancelled, which would
     * disable the cutoff for the rest of the process's life.
     */
    @Test
    void shouldSurviveAFailingCallback() {

        DailyWallClockSchedule schedule = schedule(() -> {
            throw new IllegalStateException("boom");
        });
        schedule.resetLastFired();

        schedule.checkOnce(); // must not propagate
    }
}
