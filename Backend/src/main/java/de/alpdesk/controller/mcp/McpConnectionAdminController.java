package de.alpdesk.controller.mcp;

import de.alpdesk.dto.mcp.McpAccessResponse;
import de.alpdesk.dto.oauth.SignInCompleteRequest;
import de.alpdesk.dto.oauth.SignInRequest;
import de.alpdesk.dto.oauth.SignInResponse;
import de.alpdesk.security.RequestOrigin;
import de.alpdesk.dto.mcp.McpConnectionRequest;
import de.alpdesk.dto.mcp.McpConnectionCheckResponse;
import de.alpdesk.dto.mcp.McpConnectionResponse;
import de.alpdesk.dto.mcp.McpPassResourcesRequest;
import de.alpdesk.dto.mcp.McpPassThroughRequest;
import de.alpdesk.dto.mcp.McpPromptRequest;
import de.alpdesk.dto.mcp.McpPromptResponse;
import de.alpdesk.dto.mcp.McpToolRequest;
import de.alpdesk.dto.mcp.McpToolResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.mcp.McpConnectionService;
import io.micronaut.http.HttpRequest;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's connected software (MCP), kept by the admin - like its tenant admins (TenantAdminMcpConnectionController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/mcp-connections")
@ExecuteOn(TaskExecutors.BLOCKING)
public class McpConnectionAdminController {

    private final McpConnectionService service;

    public McpConnectionAdminController(McpConnectionService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<McpConnectionResponse> list(@PathVariable Long tenantId) {
        return service.list(tenantId);
    }

    /** Connects and reads the tools at once - a server that cannot be read is told in toolsError. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<McpConnectionResponse> create(@PathVariable Long tenantId, @Body McpConnectionRequest request) {
        return HttpResponse.created(service.create(tenantId, request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}")
    public HttpResponse<McpConnectionResponse> update(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                                      @Body McpConnectionRequest request) {
        return service.update(tenantId, connectionId, request).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /**
     * Starts the sign-in at the connection's server (docs/architecture/mcp.md "Signing in to the software's server"):
     * the browser goes to the address answered and comes back to returnUrl.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/sign-in")
    public HttpResponse<SignInResponse> signIn(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                               @Body SignInRequest body, HttpRequest<?> request) {
        return service.startSignIn(tenantId, connectionId, body.returnUrl(), RequestOrigin.of(request), null)
                .map(url -> HttpResponse.ok(new SignInResponse(url.toString()))).orElseGet(HttpResponse::notFound);
    }

    /** Completes the sign-in with the server's answer the browser came back with - for the one who started it. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/sign-in/complete")
    public HttpResponse<McpConnectionResponse> completeSignIn(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                                              @Body SignInCompleteRequest body, HttpRequest<?> request) {
        return service.completeSignIn(tenantId, connectionId, body.callback(), RequestOrigin.of(request), null)
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{connectionId}/sign-in")
    public HttpResponse<?> removeSignIn(@PathVariable Long tenantId, @PathVariable Long connectionId) {
        return service.removeSignIn(tenantId, connectionId).isPresent() ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/{connectionId}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable Long connectionId) {
        return service.delete(tenantId, connectionId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Reads an A2A agent's card and times it - the check button (docs/architecture/a2a.md "Operations"). */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/check")
    public HttpResponse<McpConnectionCheckResponse> check(@PathVariable Long tenantId, @PathVariable Long connectionId) {
        return service.check(tenantId, connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/reload")
    public HttpResponse<McpConnectionResponse> reload(@PathVariable Long tenantId, @PathVariable Long connectionId) {
        return service.reload(tenantId, connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/tools/{toolId}")
    public HttpResponse<McpToolResponse> updateTool(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                                    @PathVariable Long toolId, @Body McpToolRequest request) {
        return service.updateTool(tenantId, connectionId, toolId, request.enabled(), request.changes())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/prompts/{promptId}")
    public HttpResponse<McpPromptResponse> updatePrompt(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                                        @PathVariable Long promptId, @Body McpPromptRequest request) {
        return service.updatePrompt(tenantId, connectionId, promptId, request.enabled())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{connectionId}/access")
    public HttpResponse<List<McpAccessResponse>> access(@PathVariable Long tenantId, @PathVariable Long connectionId) {
        return service.access(tenantId, connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** Whether MCP keys with the connection get it directly: {passesThrough: true}, or through ask_assistant only. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/pass-through")
    public HttpResponse<McpConnectionResponse> setPassThrough(@PathVariable Long tenantId, @PathVariable Long connectionId,
                                                              @Body McpPassThroughRequest request) {
        return service.setPassThrough(tenantId, connectionId, request.passesThrough())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** Whether MCP keys that pass the connection through read its resources too: {passesResources: true}. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/resources")
    public HttpResponse<McpConnectionResponse> setPassResources(@PathVariable Long tenantId,
                                                                @PathVariable Long connectionId,
                                                                @Body McpPassResourcesRequest request) {
        return service.setPassResources(tenantId, connectionId, request.passesResources())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }
}
