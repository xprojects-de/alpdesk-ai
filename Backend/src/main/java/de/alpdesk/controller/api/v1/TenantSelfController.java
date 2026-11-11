package de.alpdesk.controller.api.v1;

import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.dto.api.v1.TenantSelfResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.tenant.TenantService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

/**
 * The public, versioned API for client software lives under /api/v1 and is tenant-only. This
 * endpoint lets an integration check its API key and see which tenant it acts for.
 */
@Secured(Roles.TENANT)
@Controller("/api/v1/me")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "API v1")
public class TenantSelfController {

    private final TenantService tenantService;
    private final KnowledgeAreaService areas;
    private final McpConnectionService connections;

    public TenantSelfController(TenantService tenantService, KnowledgeAreaService areas,
                                McpConnectionService connections) {
        this.tenantService = tenantService;
        this.areas = areas;
        this.connections = connections;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "The tenant the API key belongs to, and the knowledge areas it finds and pushes documents into", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The calling tenant", content = @Content(schema = @Schema(implementation = TenantSelfResponse.class)))
    public HttpResponse<TenantSelfResponse> me(Authentication authentication) {
        return tenantService.findById(TenantContext.tenantId(authentication))
                .map(tenant -> HttpResponse.ok(new TenantSelfResponse(tenant.id(), tenant.name(), areas.names(tenant.id(),
                        areas.scopeOfApiKey(TenantContext.apiKeyId(authentication)).areaIds()),
                        connections.answeringAgent(tenant.id()).isPresent())))
                .orElseGet(HttpResponse::notFound);
    }
}
