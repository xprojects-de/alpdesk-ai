package de.alpdesk.entities.document;

import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.cron.CronExpression;

import java.time.Instant;
import java.time.ZoneId;

/**
 * When a knowledge source runs by itself (docs/architecture/knowledge-sources.md "When it runs") - a fixed list, no
 * schedule typed by hand: each value is a cron (six fields, in app.timezone), so the runs fall on the same times every
 * day. Not more often than hourly: every run lists the whole tree.
 */
public enum SourceSchedule {
    /** Never by itself - only "Jetzt synchronisieren". */
    MANUAL(null),
    HOURLY("0 0 * * * *"),
    EVERY_3_HOURS("0 0 */3 * * *"),
    EVERY_6_HOURS("0 0 */6 * * *"),
    EVERY_12_HOURS("0 0 */12 * * *"),
    /** At 2 o'clock: a first import with scans does not run during working hours. */
    DAILY("0 0 2 * * *");

    private final String cron;

    SourceSchedule(@Nullable String cron) {
        this.cron = cron;
    }

    /**
     * The first time after this one, in the installation's time zone; null for MANUAL. A time missed while the server
     * was down is made up for once: the scheduler finds it due and asks for the next one after now.
     */
    public @Nullable Instant nextAfter(Instant after, ZoneId zone) {
        return cron == null ? null : CronExpression.create(cron).nextTimeAfter(after.atZone(zone)).toInstant();
    }
}
