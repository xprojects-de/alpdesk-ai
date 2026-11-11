package de.alpdesk.controller.common;

import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.uri.UriBuilder;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.rules.SecurityRule;
import io.swagger.v3.oas.annotations.Hidden;

import java.net.URI;

@Secured(SecurityRule.IS_ANONYMOUS)
@Controller("/swagger")
class SwaggerController {

    private final URI swaggerUi;

    /**
     * Under a context path (micronaut.server.context-path) the redirect needs it, and Swagger UI is told it through its
     * contextPath parameter - it loads its files and the API description from there.
     */
    SwaggerController(@Value("${micronaut.server.context-path:}") String contextPath) {
        String path = contextPath.strip().replaceAll("/+$", "");
        UriBuilder ui = UriBuilder.of(path + "/swagger-ui").path("index.html");
        this.swaggerUi = (path.isEmpty() ? ui : ui.queryParam("contextPath", path)).build();
    }

    @Get
    @Hidden
    HttpResponse<?> swagger() {
        return HttpResponse.seeOther(swaggerUi);
    }
}
