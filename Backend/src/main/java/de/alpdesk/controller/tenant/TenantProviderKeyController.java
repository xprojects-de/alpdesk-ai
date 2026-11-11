package de.alpdesk.controller.tenant;

import de.alpdesk.dto.ai.ProviderKeyRequest;
import de.alpdesk.dto.ai.ProviderKeyResponse;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.security.Roles;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantService;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** A tenant's own API keys of the AI providers, kept by the admin - like its tenant admins do (ProviderKeyController). */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/tenants/{tenantId}/provider-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantProviderKeyController {

    private final ProviderKeyService service;
    private final TenantService tenants;

    public TenantProviderKeyController(ProviderKeyService service, TenantService tenants) {
        this.service = service;
        this.tenants = tenants;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<ProviderKeyResponse> list(@PathVariable Long tenantId) {
        requireTenant(tenantId);
        return service.findOwn(tenantId).stream().map(ProviderKeyResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{provider}")
    public ProviderKeyResponse save(@PathVariable Long tenantId, @PathVariable AiProvider provider,
                                    @Body ProviderKeyRequest request) {
        requireTenant(tenantId);
        return ProviderKeyResponse.from(service.saveOwn(tenantId, provider, request.apiKey()));
    }

    @Delete("/{provider}")
    public HttpResponse<?> delete(@PathVariable Long tenantId, @PathVariable AiProvider provider) {
        return service.deleteOwn(tenantId, provider) ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    private void requireTenant(Long tenantId) {
        if (tenants.findById(tenantId).isEmpty()) {
            throw ServiceException.notFound("tenant.notFound", "Tenant not found");
        }
    }
}
