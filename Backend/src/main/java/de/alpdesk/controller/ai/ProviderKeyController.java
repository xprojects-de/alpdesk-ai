package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.ProviderKeyRequest;
import de.alpdesk.dto.ai.ProviderKeyResponse;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.ai.ProviderKeyService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Body;
import io.micronaut.http.annotation.Consumes;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Delete;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Produces;
import io.micronaut.http.annotation.Put;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/**
 * The tenant's own API keys of the AI providers, kept by its tenant admins (the admin: TenantProviderKeyController); all
 * its users and its client software use them. The keys themselves never go out again.
 */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/provider-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
public class ProviderKeyController {

    private final ProviderKeyService service;

    public ProviderKeyController(ProviderKeyService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<ProviderKeyResponse> list(Authentication authentication) {
        return service.findOwn(TenantContext.tenantId(authentication)).stream().map(ProviderKeyResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{provider}")
    public ProviderKeyResponse save(Authentication authentication, @PathVariable AiProvider provider,
                                    @Body ProviderKeyRequest request) {
        return ProviderKeyResponse.from(service.saveOwn(TenantContext.tenantId(authentication), provider, request.apiKey()));
    }

    @Delete("/{provider}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable AiProvider provider) {
        return service.deleteOwn(TenantContext.tenantId(authentication), provider)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
