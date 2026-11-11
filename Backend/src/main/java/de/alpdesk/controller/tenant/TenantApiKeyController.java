package de.alpdesk.controller.tenant;

import io.swagger.v3.oas.annotations.Hidden;
import de.alpdesk.dto.common.ErrorResponse;
import de.alpdesk.dto.tenant.ApiKeyCreatedResponse;
import de.alpdesk.dto.tenant.ApiKeyRequest;
import de.alpdesk.dto.tenant.ApiKeyResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.tenant.ApiKeyService;
import de.alpdesk.services.tenant.TenantService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Put;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.List;

/** A tenant's API keys - admin only. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/api-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "Admin: Tenants")
public class TenantApiKeyController {

    private final ApiKeyService apiKeyService;
    private final TenantService tenantService;

    public TenantApiKeyController(ApiKeyService apiKeyService, TenantService tenantService) {
        this.apiKeyService = apiKeyService;
        this.tenantService = tenantService;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "List a tenant's API keys (without the keys themselves)", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The tenant's keys", content = @Content(schema = @Schema(implementation = ApiKeyResponse.class)))
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    public HttpResponse<List<ApiKeyResponse>> list(@PathVariable Long tenantId) {
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.ok(apiKeyService.list(tenantId));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    @Operation(summary = "Create an API key - the response is the only time the full key is shown", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "201", description = "Key created", content = @Content(schema = @Schema(implementation = ApiKeyCreatedResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid input")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    public HttpResponse<?> create(@PathVariable Long tenantId, @Body ApiKeyRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            return HttpResponse.badRequest(ErrorResponse.of("apiKey.nameRequired", "Name is required"));
        }
        if (tenantService.findById(tenantId).isEmpty()) {
            return HttpResponse.notFound();
        }
        return HttpResponse.created(apiKeyService.create(tenantId, request.name(), request.keepsHistory(),
                request.webSearch(), request.areaIds(), request.taskIds(), request.connectionIds()));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{keyId}")
    @Operation(summary = "Rename an API key, switch whether its chat questions are kept or change its areas, tasks or software", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "Key changed", content = @Content(schema = @Schema(implementation = ApiKeyResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid input")
    @ApiResponse(responseCode = "404", description = "Key not found for this tenant")
    public HttpResponse<?> update(@PathVariable Long tenantId, @PathVariable Long keyId, @Body ApiKeyRequest request) {
        return apiKeyService.update(tenantId, keyId, request.name(), request.keepsHistory(), request.webSearch(),
                        request.areaIds(), request.taskIds(), request.connectionIds())
                .<HttpResponse<?>>map(key -> HttpResponse.ok(apiKeyService.response(key)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{keyId}")
    @Operation(summary = "Revoke (delete) an API key", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Key revoked")
    @ApiResponse(responseCode = "404", description = "Key not found for this tenant")
    public HttpResponse<?> revoke(@PathVariable Long tenantId, @PathVariable Long keyId) {
        return apiKeyService.revoke(tenantId, keyId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
