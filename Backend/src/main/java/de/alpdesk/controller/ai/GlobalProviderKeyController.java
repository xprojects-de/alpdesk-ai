package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.ProviderKeyRequest;
import de.alpdesk.dto.ai.ProviderKeyResponse;
import de.alpdesk.entities.ai.AiProvider;
import de.alpdesk.security.Roles;
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
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The admin's global API keys, used by tenants allowed to (and billed to them). The keys themselves never go out. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/provider-keys")
@ExecuteOn(TaskExecutors.BLOCKING)
public class GlobalProviderKeyController {

    private final ProviderKeyService service;

    public GlobalProviderKeyController(ProviderKeyService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<ProviderKeyResponse> list() {
        return service.findGlobal().stream().map(ProviderKeyResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{provider}")
    public ProviderKeyResponse save(@PathVariable AiProvider provider, @Body ProviderKeyRequest request) {
        return ProviderKeyResponse.from(service.saveGlobal(provider, request.apiKey()));
    }

    @Delete("/{provider}")
    public HttpResponse<?> delete(@PathVariable AiProvider provider) {
        return service.deleteGlobal(provider) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
