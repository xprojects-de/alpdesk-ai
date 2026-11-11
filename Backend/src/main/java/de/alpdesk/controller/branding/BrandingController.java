package de.alpdesk.controller.branding;

import de.alpdesk.dto.branding.BrandingResponse;
import de.alpdesk.dto.branding.WebAppManifest;
import de.alpdesk.services.branding.BrandingService;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The installation's look, and a tenant's on its own login page /login/&lt;key&gt; - anonymous, the frontend needs it
 * before anyone logs in. It says nothing but the name, the colour and whether there is a logo; an unknown key answers
 * 404 and the page stays neutral. The web app manifest carries the installation's look to a phone's home screen.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Tag(name = "Users - sign-in")
@Controller("/branding")
public class BrandingController {

    private static final String MANIFEST = "application/manifest+json";

    private final BrandingService branding;

    public BrandingController(BrandingService branding) {
        this.branding = branding;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public BrandingResponse get() {
        return BrandingResponse.from(branding.installation());
    }

    @Get("/logo")
    public HttpResponse<byte[]> logo() {
        return Logos.response(branding.installationLogo());
    }

    /** For the browser, not for a frontend of the tenant's own: not in the published API. */
    @Hidden
    @Produces(MANIFEST)
    @Get("/manifest.webmanifest")
    public HttpResponse<WebAppManifest> manifest() {
        return HttpResponse.ok(WebAppManifest.from(branding.installation(), branding.installationLogo()))
                .contentType(MANIFEST)
                .header(HttpHeaders.CACHE_CONTROL, "no-cache");
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/login/{loginKey}")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<BrandingResponse> login(@PathVariable String loginKey) {
        return branding.forLoginKey(loginKey).map(BrandingResponse::from)
                .map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    @Get("/login/{loginKey}/logo")
    @ExecuteOn(TaskExecutors.BLOCKING)
    public HttpResponse<byte[]> loginLogo(@PathVariable String loginKey) {
        return Logos.response(branding.loginLogo(loginKey));
    }
}
