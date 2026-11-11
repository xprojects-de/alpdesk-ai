package de.alpdesk.controller.common;

import io.swagger.v3.oas.annotations.Hidden;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.core.io.ResourceResolver;
import io.micronaut.http.HttpHeaders;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import jakarta.inject.Inject;

import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The Angular app uses path-based (HTML5) routing, so a browser reload on a deep link like
 * /dashboard/tenants requests that literal path from the server instead of replaying client-side
 * navigation. There is no backend route and no matching file under the bundled Angular build for
 * such a path, so it would otherwise 404 - this serves the SPA shell (index.html) instead for the
 * app's known top-level routes (/login, the admin's /dashboard, the tenant users' /app - see
 * Frontend/src/app/app.routes.ts), letting Angular's router take over exactly as it did on the
 * original in-app navigation. These are real, explicitly
 * anonymous routes (rather than a status-404 error handler) so normal @Secured evaluation applies
 * cleanly, independent of micronaut.security.reject-not-found's handling of unmatched routes.
 * <p>
 * "/" is served from here too (controller routes win over the static-resource mapping), so that
 * every way into the app gets {@code Cache-Control: no-cache} on the shell: index.html is the one
 * file whose name does not change between builds, and a browser that heuristically cached it keeps
 * requesting the previous build's hashed chunks after a deploy - which no longer exist.
 * <p>
 * Behind a reverse proxy under a sub-path (micronaut.server.context-path, e.g. /alpdesk-ai - see
 * Backend/podman/public/README.md "Behind a reverse proxy under a sub-path") the shell gets that path as its base href:
 * the browser loads the app's files from there, and the frontend derives its API address from it.
 */
@Secured(SecurityRule.IS_ANONYMOUS)
@Hidden
@Controller
public class SpaFallbackController {

    /** The build's base href, however the build writes the tag (<base href="/">, <base href="/"/>, ...). */
    private static final Pattern ROOT_BASE = Pattern.compile("<base\\s+href=\"/\"\\s*/?>");

    @Inject
    ResourceResolver resourceResolver;

    @Value("${micronaut.server.context-path:}")
    String contextPath;

    @Get("/")
    public HttpResponse<?> root() {
        return spaShell();
    }

    /** Asked for by name, the shell needs its base href too. */
    @Get("/index.html")
    public HttpResponse<?> indexHtml() {
        return spaShell();
    }

    @Get("/login")
    public HttpResponse<?> login() {
        return spaShell();
    }

    /** A tenant's own login page (docs/architecture/branding.md). */
    @Get("/login/{loginKey}")
    public HttpResponse<?> tenantLogin(@SuppressWarnings("unused") String loginKey) {
        return spaShell();
    }

    @Get("/dashboard")
    public HttpResponse<?> dashboardRoot() {
        return spaShell();
    }

    @Get("/dashboard/{+path}")
    public HttpResponse<?> dashboardChild(@SuppressWarnings("unused") String path) {
        return spaShell();
    }

    @Get("/app")
    public HttpResponse<?> appRoot() {
        return spaShell();
    }

    @Get("/app/{+path}")
    public HttpResponse<?> appChild(@SuppressWarnings("unused") String path) {
        return spaShell();
    }

    private HttpResponse<?> spaShell() {
        Optional<URL> indexHtml = resourceResolver.getResource("classpath:public/index.html");
        return indexHtml.<HttpResponse<?>>map(url -> HttpResponse.ok(withBase(read(url), contextPath))
                        .contentType(MediaType.TEXT_HTML_TYPE)
                        .header(HttpHeaders.CACHE_CONTROL, "no-cache"))
                .orElseGet(HttpResponse::notFound);
    }

    /** The shell with the context path as its base href - unchanged without one. */
    static String withBase(String html, @Nullable String contextPath) {
        String path = contextPath == null ? "" : contextPath.strip().replaceAll("/+$", "");
        if (path.isEmpty()) {
            return html;
        }
        String base = "<base href=\"" + (path.startsWith("/") ? path : "/" + path) + "/\">";
        return ROOT_BASE.matcher(html).replaceFirst(Matcher.quoteReplacement(base));
    }

    private static String read(URL url) {
        try (InputStream in = url.openStream()) {
            return new String(in.readAllBytes(), StandardCharsets.UTF_8);
        } catch (IOException e) {
            throw new UncheckedIOException("Could not read the app's index.html", e);
        }
    }
}
