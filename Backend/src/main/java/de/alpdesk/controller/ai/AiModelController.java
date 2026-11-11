package de.alpdesk.controller.ai;

import de.alpdesk.dto.ai.AiModelRequest;
import de.alpdesk.dto.ai.AiModelResponse;
import de.alpdesk.security.Roles;
import de.alpdesk.services.ai.AiModelService;
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

/** The catalog of AI models with their prices - admin only. */
@Secured(Roles.ADMIN)
@Hidden
@Controller("/admin/models")
@ExecuteOn(TaskExecutors.BLOCKING)
public class AiModelController {

    private final AiModelService service;

    public AiModelController(AiModelService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<AiModelResponse> list() {
        return service.findAll().stream().map(AiModelResponse::from).toList();
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<AiModelResponse> create(@Body AiModelRequest request) {
        return HttpResponse.created(AiModelResponse.from(service.create(request)));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{id}")
    public HttpResponse<AiModelResponse> update(@PathVariable Long id, @Body AiModelRequest request) {
        return service.update(id, request).map(model -> HttpResponse.ok(AiModelResponse.from(model)))
                .orElseGet(HttpResponse::notFound);
    }

    @Delete("/{id}")
    public HttpResponse<?> delete(@PathVariable Long id) {
        return service.delete(id) ? HttpResponse.noContent() : HttpResponse.notFound();
    }
}
