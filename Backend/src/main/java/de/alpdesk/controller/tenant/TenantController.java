package de.alpdesk.controller.tenant;

import io.swagger.v3.oas.annotations.Hidden;
import de.alpdesk.dto.tenant.SandboxStatusResponse;
import de.alpdesk.dto.tenant.TenantRequest;
import de.alpdesk.dto.tenant.TenantResponse;
import de.alpdesk.security.Roles;
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
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
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

/** Tenant administration - admin only. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "Admin: Tenants")
public class TenantController {

    private final TenantService service;

    public TenantController(TenantService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "List all tenants", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "All tenants", content = @Content(schema = @Schema(implementation = TenantResponse.class)))
    public List<TenantResponse> list() {
        return service.findAll().stream().map(TenantResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{id}")
    @Operation(summary = "Get a tenant", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "Tenant found", content = @Content(schema = @Schema(implementation = TenantResponse.class)))
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    public HttpResponse<TenantResponse> getById(@PathVariable Long id) {
        return service.findById(id)
                .map(tenant -> HttpResponse.ok(TenantResponse.from(tenant)))
                .orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    @Operation(summary = "Create a tenant", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "201", description = "Tenant created", content = @Content(schema = @Schema(implementation = TenantResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid input")
    @ApiResponse(responseCode = "409", description = "A tenant with this name already exists")
    public HttpResponse<?> create(@Body TenantRequest request) {
        return HttpResponse.created(TenantResponse.from(service.create(request)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{id}")
    @Operation(summary = "Update a tenant", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "Tenant updated", content = @Content(schema = @Schema(implementation = TenantResponse.class)))
    @ApiResponse(responseCode = "400", description = "Invalid input")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    @ApiResponse(responseCode = "409", description = "A tenant with this name already exists")
    public HttpResponse<?> update(@PathVariable Long id, @Body TenantRequest request) {
        return service.update(id, request)
                .<HttpResponse<?>>map(tenant -> HttpResponse.ok(TenantResponse.from(tenant)))
                .orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{id}/sandbox")
    @Operation(summary = "Check the tenant's sandbox instances with its token", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "Each instance's status", content = @Content(schema = @Schema(implementation = SandboxStatusResponse.class)))
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    public HttpResponse<SandboxStatusResponse> checkSandbox(@PathVariable Long id) {
        return service.checkSandbox(id)
                .map(statuses -> HttpResponse.ok(SandboxStatusResponse.from(statuses)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{id}")
    @Operation(summary = "Delete a tenant including its API keys", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "204", description = "Tenant deleted")
    @ApiResponse(responseCode = "404", description = "Tenant not found")
    public HttpResponse<?> delete(@PathVariable Long id) {
        return service.delete(id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

}
