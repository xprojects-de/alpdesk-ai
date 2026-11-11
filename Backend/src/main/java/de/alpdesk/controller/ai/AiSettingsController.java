package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.AiSettingsResponse;
import de.alpdesk.dto.ai.TenantModelResponse;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.tenant.TenantService;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/**
 * What a tenant user needs around the AI features: the models to choose from and the settings. Changing them, and the
 * usage, is for tenant admins (DocumentController, TenantAdminUsageController).
 */
@Secured(Roles.TENANT_USER)
@Hidden
@Controller("/tenant/ai")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AiSettingsController {

    private final TenantService tenants;
    private final TenantModelService models;
    private final ProviderKeyService keys;

    public AiSettingsController(TenantService tenants, TenantModelService models, ProviderKeyService keys) {
        this.tenants = tenants;
        this.models = models;
        this.keys = keys;
    }

    /** The active models assigned to the tenant, each with whose key a call would use (none: the tenant cannot use it yet). */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/models")
    public List<TenantModelResponse> models(Authentication authentication) {
        Tenant tenant = tenant(authentication);
        return models.findUsable(tenant.id()).stream()
                .map(model -> new TenantModelResponse(model.id(), model.provider(), model.kind(), model.displayName(),
                        model.dimension(), keys.sourceOf(tenant, model).orElse(null)))
                .toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/settings")
    public AiSettingsResponse settings(Authentication authentication) {
        Tenant tenant = tenant(authentication);
        return AiSettingsResponse.from(tenant);
    }

    private Tenant tenant(Authentication authentication) {
        return tenants.findById(TenantContext.tenantId(authentication))
                .orElseThrow(() -> ServiceException.notFound("tenant.notFound", "Tenant not found"));
    }
}
