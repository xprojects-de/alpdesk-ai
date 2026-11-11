package de.alpdesk.controller.branding;

import de.alpdesk.dto.branding.BrandingResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.branding.BrandingService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/** The look of the logged-in user's tenant - its own where the admin set one, else the installation's. */
@Secured(Roles.TENANT_USER)
@Tag(name = "Users")
@SecurityRequirement(name = "UserAuth")
@Controller("/tenant/branding")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantBrandingController {

    private final BrandingService branding;

    public TenantBrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public BrandingResponse get(Authentication authentication) {
        return BrandingResponse.from(branding.forTenant(TenantContext.tenantId(authentication)));
    }

    @Get("/logo")
    public HttpResponse<byte[]> logo(Authentication authentication) {
        return Logos.response(branding.logo(TenantContext.tenantId(authentication)));
    }
}
