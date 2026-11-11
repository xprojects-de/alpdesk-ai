package de.alpdesk.services.task;

import de.alpdesk.services.task.TaskDefinition.Trigger;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.scheduling.cron.CronExpression;

import java.time.Duration;
import java.time.Instant;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.List;

/**
 * When a task's schedule is due (docs/architecture/tasks.md "Schedule"): the earliest of its triggers. An interval
 * counts from the last scheduled start - a run of 20 minutes does not shift a 5-hour rhythm; a time missed while the
 * server was down is made up for once, not once per missed time.
 */
public final class TaskSchedule {

    /** A task runs at most this often - every run asks a model about the new lines. */
    public static final Duration MIN_INTERVAL = Duration.ofMinutes(15);

    private TaskSchedule() {
    }

    /** The first time after this one; null without triggers. */
    public static @Nullable Instant nextAfter(List<Trigger> triggers, Instant after, ZoneId zone) {
        Instant next = null;
        for (Trigger trigger : triggers) {
            Instant candidate = trigger.everyMinutes() != null
                    ? after.plus(Duration.ofMinutes(trigger.everyMinutes()))
                    : CronExpression.create(trigger.cron()).nextTimeAfter(after.atZone(zone)).toInstant();
            if (next == null || candidate.isBefore(next)) {
                next = candidate;
            }
        }
        return next;
    }

    /** After a scheduled start that was due then: the next time in its rhythm - or from now, when that has passed too. */
    public static @Nullable Instant afterStart(List<Trigger> triggers, Instant due, Instant now, ZoneId zone) {
        Instant next = nextAfter(triggers, due, zone);
        return next == null || next.isAfter(now) ? next : nextAfter(triggers, now, zone);
    }

    /** Whether a cron expression fires more often than MIN_INTERVAL anywhere in its next 50 times. */
    static boolean tooOften(String cron, ZoneId zone) {
        CronExpression expression = CronExpression.create(cron);
        ZonedDateTime time = expression.nextTimeAfter(ZonedDateTime.now(zone));
        for (int i = 0; i < 50; i++) {
            ZonedDateTime next = expression.nextTimeAfter(time);
            if (Duration.between(time, next).compareTo(MIN_INTERVAL) < 0) {
                return true;
            }
            time = next;
        }
        return false;
    }
}
