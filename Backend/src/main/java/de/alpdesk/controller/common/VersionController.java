package de.alpdesk.controller.common;

import io.swagger.v3.oas.annotations.Hidden;
import de.alpdesk.dto.common.VersionResponse;
import de.alpdesk.security.Roles;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

@Secured(Roles.ADMIN)
@Hidden
@Controller("/version")
@Tag(name = "Version")
public class VersionController {

    @Value("${app.version}")
    private String version;

    @Value("${micronaut.application.name}")
    private String name;

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "get versionInfo", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "success", content = @Content(schema = @Schema(implementation = VersionResponse.class)))
    public VersionResponse getVersion() {
        return new VersionResponse(version, name);
    }
}

