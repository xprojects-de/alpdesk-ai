package de.alpdesk;

import io.micronaut.runtime.Micronaut;
import io.micronaut.openapi.annotation.OpenAPIInclude;
import io.micronaut.security.endpoints.LoginController;
import io.swagger.v3.oas.annotations.OpenAPIDefinition;
import io.swagger.v3.oas.annotations.enums.SecuritySchemeType;
import io.swagger.v3.oas.annotations.info.Info;
import io.swagger.v3.oas.annotations.security.SecurityScheme;
import io.swagger.v3.oas.annotations.tags.Tag;

/*
 * The published API documentation covers the client software's API (/api/v1) and what a frontend of the tenant's own
 * needs for its users - signing in, the account, the chat (decided 6 November 2026). The admin's and the tenant admins'
 * API (used by our frontend) is internal: its controllers carry @Hidden.
 */
@OpenAPIDefinition(
        info = @Info(title = "Zirmo API", version = "2.0",
                description = "API v1: the tenant's software - the AI features of one tenant, authenticated with the "
                        + "tenant's API key (Authorization: Bearer ai_...), issued by the AI administration. Users: what a "
                        + "frontend of the tenant's own needs for its people - sign in with POST /login ({\"username\", "
                        + "\"password\"}, header X-Login-Key with the tenant's login key for a name in several tenants) "
                        + "or POST /login/wam behind the WAM proxy, then send the access_token as Authorization: Bearer.")
)
@SecurityScheme(
        name = "BearerAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "API key",
        description = "The tenant's API key (ai_...), issued by the AI administration"
)
@SecurityScheme(
        name = "UserAuth",
        type = SecuritySchemeType.HTTP,
        scheme = "bearer",
        bearerFormat = "JWT",
        description = "A user's access_token from POST /login or POST /login/wam - valid for a day"
)
// Micronaut Security's own login endpoint, documented next to our routes (micronaut-openapi reads the class here).
@OpenAPIInclude(classes = LoginController.class, tags = @Tag(name = "Users - sign-in",
        description = "POST /login: {\"username\", \"password\"}, with the header X-Login-Key for a username that exists in "
                + "several tenants; answers access_token, username and roles"))
public class Application {

    static void main(String[] args) {
        Micronaut.run(Application.class, args);
    }
}
