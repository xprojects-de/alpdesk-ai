package de.alpdesk.controller.monitoring;

import de.alpdesk.dto.monitoring.DailyReportSentResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.monitoring.DailyReportJob;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

/** Sends the daily report now - the admin's test of the mail settings (409 without them, 502 when the server refuses). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/daily-report")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DailyReportController {

    private final DailyReportJob job;

    public DailyReportController(DailyReportJob job) {
        this.job = job;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/send")
    public DailyReportSentResponse send() {
        return new DailyReportSentResponse(job.sendNow());
    }
}
