package de.alpdesk.controller.monitoring;

import de.alpdesk.dto.monitoring.ApplicationLogResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.monitoring.ApplicationLogService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

/** The application's recent warnings and errors - the admin only. Read from memory, so no blocking executor. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/application-log")
public class ApplicationLogController {

    private final ApplicationLogService service;

    public ApplicationLogController(ApplicationLogService service) {
        this.service = service;
    }

    /** level: ERROR for errors only, WARN or none for everything; search: in logger, message and stack trace. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public ApplicationLogResponse recent(@Nullable @QueryValue String level, @Nullable @QueryValue String search) {
        return service.recent(level, search);
    }
}
