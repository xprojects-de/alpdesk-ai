package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.UsageResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.time.ZoneId;
import java.util.List;

/** The usage of the tenant admin's own tenant in a month - all its users and its client software together. */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/usage")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminUsageController {

    private final UsageService usage;
    private final ZoneId zone;

    public TenantAdminUsageController(UsageService usage, @Value("${app.timezone}") String zone) {
        this.usage = usage;
        this.zone = ZoneId.of(zone);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<UsageResponse> usage(Authentication authentication, @QueryValue @Nullable String month) {
        return usage.summarize(TenantContext.tenantId(authentication), Months.parse(month, zone)).stream()
                .map(UsageResponse::from).toList();
    }
}
