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
 * Fires once a day, at a wall-clock time. Two independent uses share this
 * class rather than duplicate its mechanics: closing out intraday
 * positions before the broker squares them off (see {@code Main}'s
 * end-of-day wiring), and refreshing the NIFTY instrument/strike window
 * each morning before the market opens (see {@code InstrumentCatalog}).
 * <p>
 * Driven by its own clock rather than by price ticks or command traffic.
 * A tick-driven EOD check would not fire when the feed is dead or the
 * instrument is quiet -- precisely when an unattended position most needs
 * closing; a command-driven morning refresh would not fire on a day
 * nobody happens to send a command before market open.
 * <p>
 * Polls rather than scheduling a single long delay: a delay computed hours
 * ahead is wrong if the machine sleeps or the clock is corrected, and the
 * failure mode there is silently missing the cutoff. Comparing wall-clock
 * time every half minute costs nothing and cannot drift.
 * <p>
 * The zone is explicit because the market's day is not the server's: a VM
 * on UTC would otherwise fire five and a half hours late.
 */
@Slf4j
public final class DailyWallClockSchedule {

    private static final long CHECK_INTERVAL_SECONDS = 30;

    private final String label;
    private final LocalTime cutoff;
    private final ZoneId zone;
    private final Runnable onCutoff;

    private ScheduledExecutorService executor;
    private volatile LocalDate lastFired;

    /**
     * @param label     what this firing does, used only in logs and the
     *                  poller thread's name (e.g. "end-of-day close",
     *                  "morning instrument refresh") -- purely descriptive,
     *                  does not affect scheduling
     * @param cutoff    the wall-clock time to fire at
     * @param zone      the zone {@code cutoff} is interpreted in
     * @param onCutoff  run once, the first time {@code cutoff} is reached
     *                  on a day it hasn't already fired
     */
    public DailyWallClockSchedule(String label, LocalTime cutoff, ZoneId zone, Runnable onCutoff) {
        this.label = Objects.requireNonNull(label, "label must not be null");
        this.cutoff = Objects.requireNonNull(cutoff, "cutoff must not be null");
        this.zone = Objects.requireNonNull(zone, "zone must not be null");
        this.onCutoff = Objects.requireNonNull(onCutoff, "onCutoff must not be null");
    }

    public void start() {
        // Starting mid-session must not immediately fire for a cutoff that
        // has already passed today -- that would re-run today's firing a
        // second time (or, for the EOD case, close positions the user
        // deliberately reopened after the cutoff).
        lastFired = LocalDate.now(zone);

        executor = Executors.newSingleThreadScheduledExecutor(r -> {
            Thread t = new Thread(r, "daily-schedule-" + label.replace(' ', '-'));
            t.setDaemon(true);
            return t;
        });
        executor.scheduleAtFixedRate(this::checkOnce, CHECK_INTERVAL_SECONDS, CHECK_INTERVAL_SECONDS,
                TimeUnit.SECONDS);

        log.info("{} scheduled for {} {}", label, cutoff, zone);
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
                log.info("{} cutoff {} reached", label, cutoff);
                onCutoff.run();
            }
        } catch (Exception e) {
            // A scheduled task that throws is silently cancelled, which
            // would disable this schedule for the rest of the process's life.
            log.error("{} check failed", label, e);
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
