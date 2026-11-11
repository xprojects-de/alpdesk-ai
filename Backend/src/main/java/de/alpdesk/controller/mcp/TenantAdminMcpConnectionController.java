package de.alpdesk.controller.mcp;

import de.alpdesk.dto.mcp.McpAccessResponse;
import de.alpdesk.dto.oauth.SignInCompleteRequest;
import de.alpdesk.dto.oauth.SignInRequest;
import de.alpdesk.dto.oauth.SignInResponse;
import de.alpdesk.security.RequestOrigin;
import de.alpdesk.dto.mcp.McpConnectionRequest;
import de.alpdesk.dto.mcp.McpConnectionCheckResponse;
import de.alpdesk.dto.mcp.McpConnectionResponse;
import de.alpdesk.dto.mcp.McpPassThroughRequest;
import de.alpdesk.dto.mcp.McpPromptRequest;
import de.alpdesk.dto.mcp.McpPromptResponse;
import de.alpdesk.dto.mcp.McpToolRequest;
import de.alpdesk.dto.mcp.McpToolResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The connected software (MCP) of the tenant admin's own tenant - like the admin's (McpConnectionAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/mcp-connections")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminMcpConnectionController {

    private final McpConnectionService service;

    public TenantAdminMcpConnectionController(McpConnectionService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<McpConnectionResponse> list(Authentication authentication) {
        return service.list(tenantId(authentication));
    }

    /** Connects and reads the tools at once - a server that cannot be read is told in toolsError. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<McpConnectionResponse> create(Authentication authentication, @Body McpConnectionRequest request) {
        return HttpResponse.created(service.create(tenantId(authentication), request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}")
    public HttpResponse<McpConnectionResponse> update(Authentication authentication, @PathVariable Long connectionId,
                                                      @Body McpConnectionRequest request) {
        return service.update(tenantId(authentication), connectionId, request).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /**
     * Starts the sign-in at the connection's server (docs/architecture/mcp.md "Signing in to the software's server"):
     * the browser goes to the address answered and comes back to returnUrl.
     */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/sign-in")
    public HttpResponse<SignInResponse> signIn(Authentication authentication, @PathVariable Long connectionId,
                                               @Body SignInRequest body, HttpRequest<?> request) {
        return service.startSignIn(tenantId(authentication), connectionId, body.returnUrl(), RequestOrigin.of(request), TenantContext.userId(authentication))
                .map(url -> HttpResponse.ok(new SignInResponse(url.toString()))).orElseGet(HttpResponse::notFound);
    }

    /** Completes the sign-in with the server's answer the browser came back with - for the one who started it. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/sign-in/complete")
    public HttpResponse<McpConnectionResponse> completeSignIn(Authentication authentication, @PathVariable Long connectionId,
                                                              @Body SignInCompleteRequest body, HttpRequest<?> request) {
        return service.completeSignIn(tenantId(authentication), connectionId, body.callback(), RequestOrigin.of(request),
                        TenantContext.userId(authentication))
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{connectionId}/sign-in")
    public HttpResponse<?> removeSignIn(Authentication authentication, @PathVariable Long connectionId) {
        return service.removeSignIn(tenantId(authentication), connectionId).isPresent() ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/{connectionId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long connectionId) {
        return service.delete(tenantId(authentication), connectionId) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Reads an A2A agent's card and times it - the check button (docs/architecture/a2a.md "Operations"). */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/check")
    public HttpResponse<McpConnectionCheckResponse> check(Authentication authentication, @PathVariable Long connectionId) {
        return service.check(tenantId(authentication), connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{connectionId}/reload")
    public HttpResponse<McpConnectionResponse> reload(Authentication authentication, @PathVariable Long connectionId) {
        return service.reload(tenantId(authentication), connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/tools/{toolId}")
    public HttpResponse<McpToolResponse> updateTool(Authentication authentication, @PathVariable Long connectionId,
                                                    @PathVariable Long toolId, @Body McpToolRequest request) {
        return service.updateTool(tenantId(authentication), connectionId, toolId, request.enabled(), request.changes())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/prompts/{promptId}")
    public HttpResponse<McpPromptResponse> updatePrompt(Authentication authentication, @PathVariable Long connectionId,
                                                        @PathVariable Long promptId, @Body McpPromptRequest request) {
        return service.updatePrompt(tenantId(authentication), connectionId, promptId, request.enabled())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{connectionId}/access")
    public HttpResponse<List<McpAccessResponse>> access(Authentication authentication, @PathVariable Long connectionId) {
        return service.access(tenantId(authentication), connectionId).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** Whether MCP keys with the connection get it directly: {passesThrough: true}, or through ask_assistant only. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{connectionId}/pass-through")
    public HttpResponse<McpConnectionResponse> setPassThrough(Authentication authentication, @PathVariable Long connectionId,
                                                              @Body McpPassThroughRequest request) {
        return service.setPassThrough(tenantId(authentication), connectionId, request.passesThrough())
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    private static Long tenantId(Authentication authentication) {
        return TenantContext.tenantId(authentication);
    }
}
