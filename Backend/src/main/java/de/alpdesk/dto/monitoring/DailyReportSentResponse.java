package de.alpdesk.dto.monitoring;

import io.micronaut.serde.annotation.Serdeable;

/** The daily report was sent at the admin's request - to this many recipients. */
@Serdeable
public record DailyReportSentResponse(int recipients) {
}
