package de.alpdesk.controller.docsite;

import de.alpdesk.services.docsite.DocSiteService;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpMethod;
import io.micronaut.http.HttpRequest;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.MutableHttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.server.cors.CrossOrigin;
import io.micronaut.http.server.types.files.StreamedFile;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;

import java.net.URI;
import java.util.List;

/**
 * The files of a handbook's website (docs/architecture/doc-sites.md "Security"), reached by the address
 * DocSiteService.address hands out - anonymous, the token in the path is the check, as an iframe sends no
 * Authorization header. Every file is sandboxed: the browser treats it as a foreign origin, so the uploaded HTML and
 * its scripts never reach the app's storage, token or API, also when the address is opened in a tab of its own.
 * <p>
 * A sandboxed page's origin is "null", and it sends that origin with requests - Firefox with every one, every browser
 * with a font's. Without a rule of its own the CORS filter refuses them on localhost and gives a font no
 * Access-Control-Allow-Origin anywhere: the website's styles and fonts would be dropped. So the website's own requests,
 * and only those, may read its files - without credentials, which the files never need.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@CrossOrigin(allowedOriginsRegex = "^null$", allowedMethods = HttpMethod.GET)
@Hidden
@Controller("/doc-sites")
@ExecuteOn(TaskExecutors.BLOCKING)
public class DocSiteFilesController {

    /**
     * Scripts and links in a new tab, nothing else (no allow-same-origin). A tab the website opens leaves the sandbox:
     * it shows another site or the app as they are.
     */
    static final String SANDBOX = "sandbox allow-scripts allow-popups allow-popups-to-escape-sandbox";

    private final DocSiteService service;
    private final String frameAncestors;

    /** The app's own pages may frame the website - and the Angular dev server, which runs on another origin. */
    public DocSiteFilesController(DocSiteService service,
                                  @Value("${micronaut.server.cors.configurations.ui.allowed-origins:}") List<String> frontendOrigins) {
        this.service = service;
        this.frameAncestors = "frame-ancestors 'self'" + (frontendOrigins.isEmpty() ? "" : " " + String.join(" ", frontendOrigins));
    }

    /**
     * The website's start: its index.html - always below "/", so its relative links resolve inside the website. Only a
     * token this installation signed is redirected: the path variable arrives decoded, and "%2F%2Fevil.example" would
     * otherwise send the browser to another site.
     */
    @Get("/{token}")
    public HttpResponse<?> start(HttpRequest<?> request, @PathVariable String token) {
        if (!request.getPath().endsWith("/")) {
            return service.signed(token) ? secured(HttpResponse.redirect(URI.create(token + "/"))) : HttpResponse.notFound();
        }
        return file(token, "");
    }

    @Get("/{token}/{+path}")
    public HttpResponse<?> file(HttpRequest<?> request, @PathVariable String token, @PathVariable String path) {
        return file(token, request.getPath().endsWith("/") && !path.endsWith("/") ? path + "/" : path);
    }

    private HttpResponse<?> file(String token, String path) {
        return service.open(token, path)
                .<HttpResponse<?>>map(served -> switch (served) {
                    case DocSiteService.Served.File file ->
                            secured(HttpResponse.ok(new StreamedFile(file.content(), MediaType.of(file.contentType()))));
                    case DocSiteService.Served.Folder folder ->
                            secured(HttpResponse.redirect(URI.create(folder.location())));
                })
                .orElseGet(HttpResponse::notFound);
    }

    /**
     * The sandbox and who may frame it (ResponseHeaders leaves an answer's own policy alone); no referrer, so the
     * token in the address never reaches a site the website links to; kept briefly, by this browser only.
     */
    private <T> MutableHttpResponse<T> secured(MutableHttpResponse<T> response) {
        return response
                .header("Content-Security-Policy", SANDBOX + "; " + frameAncestors)
                .header("X-Frame-Options", "SAMEORIGIN")
                .header("Referrer-Policy", "no-referrer")
                .header("Cache-Control", "private, max-age=300");
    }
}
