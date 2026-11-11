package de.alpdesk.controller.tenant;

import de.alpdesk.dto.tenant.ApiKeyCreatedResponse;
import de.alpdesk.dto.tenant.ApiKeyRequest;
import de.alpdesk.dto.tenant.ApiKeyResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.ApiKeyService;
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
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The client software's API keys of the tenant admin's own tenant - like the admin's (TenantApiKeyController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/api-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminApiKeyController {

    private final ApiKeyService apiKeyService;

    public TenantAdminApiKeyController(ApiKeyService apiKeyService) {
        this.apiKeyService = apiKeyService;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<ApiKeyResponse> list(Authentication authentication) {
        return apiKeyService.list(TenantContext.tenantId(authentication));
    }

    /** The response is the only time the full key is shown. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<ApiKeyCreatedResponse> create(Authentication authentication, @Body ApiKeyRequest request) {
        if (request == null || request.name() == null || request.name().isBlank()) {
            throw ServiceException.badRequest("apiKey.nameRequired", "Name is required");
        }
        return HttpResponse.created(apiKeyService.create(TenantContext.tenantId(authentication), request.name(),
                request.keepsHistory(), request.webSearch(), request.areaIds(), request.taskIds(), request.connectionIds()));
    }

    /** Renames the key, switches whether its chat questions are kept or changes its areas, tasks or software. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{keyId}")
    public HttpResponse<ApiKeyResponse> update(Authentication authentication, @PathVariable Long keyId,
                                               @Body ApiKeyRequest request) {
        return apiKeyService.update(TenantContext.tenantId(authentication), keyId, request.name(), request.keepsHistory(),
                        request.webSearch(), request.areaIds(), request.taskIds(), request.connectionIds())
                .map(key -> HttpResponse.ok(apiKeyService.response(key)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{keyId}")
    public HttpResponse<?> revoke(Authentication authentication, @PathVariable Long keyId) {
        return apiKeyService.revoke(TenantContext.tenantId(authentication), keyId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
