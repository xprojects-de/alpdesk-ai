package de.alpdesk.controller.task;

import de.alpdesk.dto.task.TaskDraftModels;
import de.alpdesk.dto.task.TaskDraftRequest;
import de.alpdesk.dto.task.TaskDraftResponse;
import de.alpdesk.dto.task.TaskReferenceResponse;
import de.alpdesk.dto.task.TaskRequest;
import de.alpdesk.dto.task.TaskResponse;
import de.alpdesk.dto.task.TaskRunResponse;
import de.alpdesk.dto.task.TaskRunStarted;
import de.alpdesk.dto.task.TaskRunSummary;
import de.alpdesk.dto.task.TaskSourceRequest;
import de.alpdesk.dto.task.TaskSummaryResponse;
import de.alpdesk.dto.task.TaskValidationResponse;
import de.alpdesk.entities.task.StartedBy;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.task.TaskDraftService;
import de.alpdesk.services.task.TaskReference;
import de.alpdesk.services.task.TaskService;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
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
import io.micronaut.http.annotation.QueryValue;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Hidden;

import java.util.List;

/** The scheduled tasks of the tenant admin's own tenant - like the admin's (TaskAdminController). */
@Secured(Roles.TENANT_ADMIN)
@Hidden
@Controller("/tenant/admin/tasks")
@ExecuteOn(TaskExecutors.BLOCKING)
public class TenantAdminTaskController {

    private final TaskService service;
    private final TaskDraftService drafts;
    private final TaskReference reference;

    public TenantAdminTaskController(TaskService service, TaskDraftService drafts, TaskReference reference) {
        this.service = service;
        this.drafts = drafts;
        this.reference = reference;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    public List<TaskSummaryResponse> list(Authentication authentication) {
        return service.list(tenantId(authentication), StartedBy.TENANT_ADMIN);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{taskId}")
    public HttpResponse<TaskResponse> get(Authentication authentication, @PathVariable Long taskId) {
        return service.get(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId)
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** The mistakes with their lines, or the steps - nothing is saved. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/validate")
    public TaskValidationResponse validate(Authentication authentication, @Body TaskSourceRequest request) {
        return service.validate(tenantId(authentication), StartedBy.TENANT_ADMIN, request.source());
    }

    /** A draft of a task written by the tenant's chat model - checked, never saved. */
    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post("/draft")
    public TaskDraftResponse draft(Authentication authentication, @Body TaskDraftRequest request) {
        return drafts.draft(tenantId(authentication), StartedBy.TENANT_ADMIN, TenantContext.userId(authentication),
                request);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/draft-models")
    public TaskDraftModels draftModels(Authentication authentication) {
        return drafts.models(tenantId(authentication), StartedBy.TENANT_ADMIN);
    }

    /** The format's reference in this language (en, de), else English. */
    @Produces(MediaType.APPLICATION_JSON)
    @Get("/reference")
    public TaskReferenceResponse reference(Authentication authentication, @Nullable @QueryValue String language) {
        tenantId(authentication);
        return new TaskReferenceResponse(reference.markdown(language == null ? "en" : language));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Post
    public HttpResponse<TaskResponse> create(Authentication authentication, @Body TaskRequest request) {
        return HttpResponse.created(service.create(tenantId(authentication), StartedBy.TENANT_ADMIN, request));
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Consumes(MediaType.APPLICATION_JSON)
    @Put("/{taskId}")
    public HttpResponse<TaskResponse> update(Authentication authentication, @PathVariable Long taskId,
                                             @Body TaskRequest request) {
        return service.update(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId, request)
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Delete("/{taskId}")
    public HttpResponse<?> delete(Authentication authentication, @PathVariable Long taskId) {
        return service.delete(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    /** Runs in the background - 202 with the run's id. */
    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{taskId}/runs")
    public HttpResponse<TaskRunStarted> start(Authentication authentication, @PathVariable Long taskId) {
        return service.start(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId,
                        TenantContext.userId(authentication))
                .map(started -> HttpResponse.status(HttpStatus.ACCEPTED).body(started))
                .orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{taskId}/runs")
    public HttpResponse<List<TaskRunSummary>> runs(Authentication authentication, @PathVariable Long taskId,
                                                   @Nullable @QueryValue Integer limit,
                                                   @Nullable @QueryValue Integer offset) {
        return service.runs(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId, limit == null ? 50 : limit,
                offset == null ? 0 : offset).map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{taskId}/runs/{runId}")
    public HttpResponse<TaskRunResponse> run(Authentication authentication, @PathVariable Long taskId,
                                             @PathVariable Long runId) {
        return service.run(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId, runId)
                .map(HttpResponse::ok).orElseGet(HttpResponse::notFound);
    }

    /** A queued run ends at once, a running one before its next step or round; 409 for one that has ended. */
    @Post("/{taskId}/runs/{runId}/cancel")
    public HttpResponse<?> cancel(Authentication authentication, @PathVariable Long taskId, @PathVariable Long runId) {
        return service.cancel(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId, runId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/{taskId}/runs/{runId}")
    public HttpResponse<?> deleteRun(Authentication authentication, @PathVariable Long taskId, @PathVariable Long runId) {
        return service.deleteRun(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId, runId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    @Delete("/{taskId}/runs")
    public HttpResponse<?> deleteRuns(Authentication authentication, @PathVariable Long taskId) {
        return service.deleteRuns(tenantId(authentication), StartedBy.TENANT_ADMIN, taskId)
                ? HttpResponse.noContent() : HttpResponse.notFound();
    }

    private static Long tenantId(Authentication authentication) {
        return TenantContext.tenantId(authentication);
    }
}
