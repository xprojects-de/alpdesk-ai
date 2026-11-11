package de.alpdesk.controller.mcp;

import de.alpdesk.dto.mcp.McpKeyCreatedResponse;
import de.alpdesk.dto.mcp.McpKeyRequest;
import de.alpdesk.dto.mcp.McpKeyResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.mcp.McpKeyService;
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
import io.micronaut.security.authentication.Authentication;
import io.micronaut.security.annotation.Secured;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The MCP keys of the tenant admin's own tenant - like the admin's (McpKeyAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/mcp-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminMcpKeyController {

    private final McpKeyService keys;

    public TenantAdminMcpKeyController(McpKeyService keys) {
        this.keys = keys;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<McpKeyResponse> list(Authentication authentication) {
        return keys.list(TenantContext.tenantId(authentication));
    }

    /** The response is the only time the key is shown. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<McpKeyCreatedResponse> create(Authentication authentication, @Body McpKeyRequest request) {
        return HttpResponse.created(keys.create(TenantContext.tenantId(authentication), request.name(),
                request.description(), request.webSearch(), request.areaIds(), request.connectionIds(), request.taskIds(),
                request.mcp(), request.a2a()));
    }

    /** Renames the key, switches its web search or changes its knowledge areas or its software. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{keyId}")
    public HttpResponse<McpKeyResponse> update(Authentication authentication, @PathVariable Long keyId,
                                               @Body McpKeyRequest request) {
        return keys.update(TenantContext.tenantId(authentication), keyId, request.name(), request.description(),
                request.webSearch(),
                request.areaIds(), request.connectionIds(), request.taskIds(), request.mcp(), request.a2a()).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** A new secret for the key - the previous one stops working; shown only in this response. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{keyId}/renew")
    public HttpResponse<McpKeyCreatedResponse> renew(Authentication authentication, @PathVariable Long keyId) {
        return keys.renew(TenantContext.tenantId(authentication), keyId).map(HttpResponse::ok)
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{keyId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long keyId) {
        return keys.delete(TenantContext.tenantId(authentication), keyId) ? HttpResponse.noContent()
                : HttpResponse.notFound();
    }
}
