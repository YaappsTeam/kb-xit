package com.kbquants.live;

import lombok.extern.slf4j.Slf4j;

import java.time.LocalDate;
import java.time.LocalTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.Objects;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;

/**
 * Fires once a day, at a wall-clock time, to close out intraday positions
 * before the broker squares them off at whatever price it likes.
 * <p>
 * Driven by its own clock rather than by price ticks. A tick-driven check
 * would not fire when the feed is dead or the instrument is quiet -- which
 * is precisely when an unattended position most needs closing.
 * <p>
 * Polls rather than scheduling a single long delay: a delay computed hours
 * ahead is wrong if the machine sleeps or the clock is corrected, and the
 * failure mode there is silently missing the cutoff. Comparing wall-clock
 * time every half minute costs nothing and cannot drift.
 * <p>
 * The zone is explicit because the market's day is not the server's: a VM
 * on UTC would otherwise square off five and a half hours late.
 */
@Slf4j
public final class EndOfDaySchedule {

    private static final long CHECK_INTERVAL_SECONDS = 30;

    private final LocalTime cutoff;
    private final ZoneId zone;
    private final Runnable onCutoff;

    private ScheduledExecutorService executor;
    private volatile LocalDate lastFired;

    public EndOfDaySchedule(LocalTime cutoff, ZoneId zone, Runnable onCutoff) {
        this.cutoff = Objects.requireNonNull(cutoff, "cutoff must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.onCutoff = Objects.requireNonNull(onCutoff, "onCutoff must not be null");
    }

    public void start() {
        // Starting mid-session must not immediately fire for a cutoff that
        // has already passed today -- that would close positions the user
        // deliberately reopened after it.
        lastFired = LocalDate.now(zone);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "end-of-day-schedule");
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::checkOnce, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("End-of-day close scheduled for {} {}", cutoff, zone);
    }

    public void stop() {
        if (executor != null) {
            executor.shutdownNow();
        }
    }

    void checkOnce() {
        try {
            ZonedDateTime now = ZonedDateTime.now(zone);
            if (shouldFire(now.toLocalDate(), now.toLocalTime())) {
                lastFired = now.toLocalDate();
                log.info("End-of-day cutoff {} reached", cutoff);
                onCutoff.run();
            }
        } catch (Exception e) {
            // A scheduled task that throws is silently cancelled, which
            // would disable the cutoff for the rest of the process's life.
            log.error("End-of-day check failed", e);
        }
    }

    boolean shouldFire(LocalDate today, LocalTime now) {
        return !today.equals(lastFired) && !now.isBefore(cutoff);
    }

    /** Test seam: pretend the last firing was on an earlier day. */
    void resetLastFired() {
        lastFired = null;
    }
}
