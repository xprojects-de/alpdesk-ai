package de.alpdesk.services.task

import de.alpdesk.services.task.TaskDefinition.Trigger
import spock.lang.Specification

import java.time.Instant
import java.time.ZoneId

/** When a schedule is due (TaskSchedule): the earliest trigger, a rhythm kept, a downtime made up for once. */
class TaskScheduleSpec extends Specification {

    static final ZoneId BERLIN = ZoneId.of("Europe/Berlin")

    def "an interval counts from the last scheduled start, not from now - #situation"() {
        expect:
        TaskSchedule.afterStart([new Trigger(300, null, 1)], Instant.parse(due), Instant.parse(now), BERLIN) == Instant.parse(next)

        where:
        situation                         | due                    | now                    || next
        "started on time"                 | "2026-10-21T06:00:00Z" | "2026-10-21T06:00:30Z" || "2026-10-21T11:00:00Z"
        "a downtime of a day: once, then from now" | "2026-10-20T06:00:00Z" | "2026-10-21T06:00:30Z" || "2026-10-21T11:00:30Z"
    }

    def "a cron goes by the installation's time zone, and a time missed is not made up for twice"() {
        given:
        def weekdays = new Trigger(null, "0 30 6 * * MON-FRI", 1)

        expect: "Friday 6:30 in Berlin, then Monday"
        TaskSchedule.afterStart([weekdays], Instant.parse("2026-10-23T04:30:00Z"), Instant.parse("2026-10-23T04:30:10Z"), BERLIN) ==
                Instant.parse("2026-10-26T05:30:00Z")
        TaskSchedule.afterStart([weekdays], Instant.parse("2026-10-19T04:30:00Z"), Instant.parse("2026-10-23T05:00:00Z"), BERLIN) ==
                Instant.parse("2026-10-26T05:30:00Z")
    }

    def "several triggers: the earliest; none: no schedule"() {
        expect:
        TaskSchedule.nextAfter([new Trigger(24 * 60, null, 1), new Trigger(60, null, 2)], Instant.parse("2026-10-21T06:00:00Z"), BERLIN) ==
                Instant.parse("2026-10-21T07:00:00Z")
        TaskSchedule.nextAfter([], Instant.now(), BERLIN) == null
    }

    def "a cron firing more often than every 15 minutes is refused"() {
        expect:
        TaskSchedule.tooOften(cron, BERLIN) == tooOften

        where:
        cron                 || tooOften
        "0 */5 * * * *"      || true
        "0 0,10 * * * *"     || true
        "0 */15 * * * *"     || false
        "0 30 6 * * MON-FRI" || false
    }
}
