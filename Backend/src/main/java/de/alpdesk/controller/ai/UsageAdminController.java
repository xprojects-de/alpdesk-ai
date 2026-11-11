package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.UsageResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.ai.UsageService;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.time.ZoneId;
import java.util.List;

/** The usage of all tenants in a month - what to bill (key source GLOBAL) - admin only. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/usage")
@ExecuteOn(TaskExecutors.BLOCKING)
public class UsageAdminController {

    private final UsageService service;
    private final ZoneId zone;

    public UsageAdminController(UsageService service, @Value("${app.timezone}") String zone) {
        this.service = service;
        this.zone = ZoneId.of(zone);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<UsageResponse> list(@QueryValue @Nullable String month, @QueryValue @Nullable Long tenantId) {
        return service.summarize(tenantId, Months.parse(month, zone)).stream().map(UsageResponse::from).toList();
    }
}
