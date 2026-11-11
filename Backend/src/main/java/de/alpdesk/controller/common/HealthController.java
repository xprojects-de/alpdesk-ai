package de.alpdesk.controller.common;

import io.swagger.v3.oas.annotations.Hidden;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * A trivial, unauthenticated liveness probe the frontend polls to detect whether the backend
 * process is reachable at all, independent of login state - a proper authentication failure
 * (401/403) already has its own handling via the frontend's auth interceptor, and mixing that
 * concern into this endpoint would make a merely-expired session look like the backend being
 * down. Anonymous and side-effect-free by design, so it can never itself become a reason the
 * poll fails.
 * <p>
 * Deliberately not "/health": micronaut-management is on the classpath transitively and already
 * claims that path for its own aggregated {@code HealthEndpoint} (JDBC, disk space, deadlocked
 * threads, ...), which would collide with a controller-defined route of the same name and is
 * also unsuitable here anyway - it does real work (e.g. a DB query) on every call, not the
 * near-zero-cost ping this watchdog is supposed to be.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Hidden
@Controller("/watchdog")
@Tag(name = "Watchdog")
public class HealthController {

    @Get
    @Operation(summary = "Liveness probe for the frontend's backend-reachability watchdog")
    @ApiResponse(responseCode = "200", description = "The backend process is up and responding")
    public void check() {
    }
}
