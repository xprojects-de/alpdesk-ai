package de.alpdesk.controller.branding;

import de.alpdesk.dto.branding.TenantBrandingRequest;
import de.alpdesk.dto.branding.TenantBrandingResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.branding.BrandingService;
import de.alpdesk.services.common.ServiceException;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Part;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.multipart.CompletedFileUpload;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.io.IOException;
import java.util.Map;

/** A tenant's own look - set by the admin only; the tenant's users see it in the tenant area. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/branding")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantBrandingAdminController {

    private final BrandingService branding;

    public TenantBrandingAdminController(BrandingService branding) {
        this.branding = branding;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public TenantBrandingResponse get(@PathVariable Long tenantId) {
        return response(tenantId);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put
    public TenantBrandingResponse save(@PathVariable Long tenantId, @Body TenantBrandingRequest request) {
        branding.save(tenantId, request.name(), request.primaryColor());
        return response(tenantId);
    }

    /** The tenant's own logo, without the installation's - the admin's preview. */
    @Get("/logo")
    public HttpResponse<byte[]> logo(@PathVariable Long tenantId) {
        return Logos.response(branding.ownLogo(tenantId));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.MULTIPART_FORM_DATA)
    @Put("/logo")
    public TenantBrandingResponse saveLogo(@PathVariable Long tenantId, @Part("file") CompletedFileUpload file)
            throws IOException {
        // The server takes requests up to a ZIP archive's size: a logo is not read when it is larger than a logo may be.
        if (file.getSize() > BrandingService.MAX_LOGO_BYTES) {
            throw ServiceException.badRequest("branding.logoTooLarge", "The logo may have at most "
                    + BrandingService.MAX_LOGO_BYTES / 1024 + " KB",
                    Map.of("maxKb", String.valueOf(BrandingService.MAX_LOGO_BYTES / 1024)));
        }
        branding.saveLogo(tenantId, file.getBytes());
        return response(tenantId);
    }

    /** A new address for the tenant's login page - the old one shows the neutral page from now on. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/login-key")
    public TenantBrandingResponse renewLoginKey(@PathVariable Long tenantId) {
        branding.renewLoginKey(tenantId);
        return response(tenantId);
    }

    @Delete("/logo")
    public HttpResponse<?> deleteLogo(@PathVariable Long tenantId) {
        return branding.deleteLogo(tenantId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    private TenantBrandingResponse response(Long tenantId) {
        return TenantBrandingResponse.from(branding.findOwn(tenantId), branding.defaultName(), branding.defaultColor(),
                branding.loginKey(tenantId));
    }
}
