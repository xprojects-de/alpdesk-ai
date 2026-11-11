package de.alpdesk.controller.branding;

import de.alpdesk.dto.branding.LoginKeyResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.branding.BrandingService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

/**
 * The address of the tenant's own login page, for the tenant admins to hand to their people - they read it only: the
 * look and a new address are the admin's.
 */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/branding")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminBrandingController {

    private final BrandingService branding;

    public TenantAdminBrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/login-key")
    public LoginKeyResponse loginKey(Authentication authentication) {
        return new LoginKeyResponse(branding.loginKey(TenantContext.tenantId(authentication)));
    }
}
