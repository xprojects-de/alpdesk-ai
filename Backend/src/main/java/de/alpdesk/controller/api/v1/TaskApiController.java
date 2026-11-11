package de.alpdesk.controller.api.v1;

import de.alpdesk.dto.api.v1.ApiTaskResponse;
import de.alpdesk.dto.api.v1.ApiTaskRunResponse;
import de.alpdesk.dto.api.v1.ApiTaskRunStarted;
import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.security.Roles;
import de.alpdesk.security.TenantContext;
import de.alpdesk.services.task.TaskAnswer;
import de.alpdesk.services.task.TaskService;
import io.micronaut.http.HttpResponse;
import io.micronaut.http.HttpStatus;
import io.micronaut.http.MediaType;
import io.micronaut.http.annotation.Controller;
import io.micronaut.http.annotation.Get;
import io.micronaut.http.annotation.PathVariable;
import io.micronaut.http.annotation.Post;
import io.micronaut.http.annotation.Produces;
import io.micronaut.scheduling.TaskExecutors;
import io.micronaut.scheduling.annotation.ExecuteOn;
import io.micronaut.security.annotation.Secured;
import io.micronaut.security.authentication.Authentication;
import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.security.SecurityRequirement;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

/**
 * The client software starts the tenant's scheduled tasks (docs/architecture/tasks.md "/api/v1"): only the tasks a
 * tenant admin assigned to its API key - any other answers 404. A run runs in the background; the software asks for it
 * until it has ended and gets what it found - the check points not ok with their evidence lines, not the lines read.
 */
@Secured(Roles.TENANT)
@Controller("/api/v1/tasks")
@ExecuteOn(TaskExecutors.BLOCKING)
@Tag(name = "API v1")
public class TaskApiController {

    private final TaskService service;

    public TaskApiController(TaskService service) {
        this.service = service;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get
    @Operation(summary = "The scheduled tasks this API key may start", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The key's tasks", content = @Content(schema = @Schema(implementation = ApiTaskResponse.class)))
    public List<ApiTaskResponse> list(Authentication authentication) {
        Long tenantId = TenantContext.tenantId(authentication);
        List<ApiTaskResponse> list = new ArrayList<>();
        for (Task task : service.tasksOfApiKey(tenantId, TenantContext.apiKeyId(authentication))) {
            Optional<TaskRun> last = service.lastRun(tenantId, task.id());
            list.add(new ApiTaskResponse(task.id(), task.name(), task.active(), last.map(run -> run.state().name()).orElse(null),
                    last.map(TaskRun::createdAt).orElse(null)));
        }
        return list;
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Post("/{taskId}/runs")
    @Operation(summary = "Start a run of the task now - it runs in the background", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "202", description = "Started", content = @Content(schema = @Schema(implementation = ApiTaskRunStarted.class)))
    @ApiResponse(responseCode = "404", description = "No task of this id assigned to the key")
    @ApiResponse(responseCode = "409", description = "A run of the task is still open (task.running), the task is switched off (task.inactive) or the tenant may not run tasks (task.notAllowed)")
    public HttpResponse<ApiTaskRunStarted> start(Authentication authentication, @PathVariable Long taskId) {
        return service.startByApiKey(TenantContext.tenantId(authentication), TenantContext.apiKeyId(authentication), taskId)
                .map(started -> HttpResponse.status(HttpStatus.ACCEPTED).body(new ApiTaskRunStarted(started.runId())))
                .orElseGet(HttpResponse::notFound);
    }

    @Produces(MediaType.APPLICATION_JSON)
    @Get("/{taskId}/runs/{runId}")
    @Operation(summary = "A run of the task and what it found", security = @SecurityRequirement(name = "BearerAuth"))
    @ApiResponse(responseCode = "200", description = "The run", content = @Content(schema = @Schema(implementation = ApiTaskRunResponse.class)))
    @ApiResponse(responseCode = "404", description = "No such run of a task assigned to the key")
    public HttpResponse<ApiTaskRunResponse> run(Authentication authentication, @PathVariable Long taskId,
                                                @PathVariable Long runId) {
        Long tenantId = TenantContext.tenantId(authentication);
        if (service.keyTask(tenantId, TenantContext.apiKeyId(authentication), taskId).isEmpty()) {
            return HttpResponse.notFound();
        }
        return service.findRun(tenantId, taskId, runId)
                .map(run -> HttpResponse.ok(response(tenantId, run)))
                .orElseGet(HttpResponse::notFound);
    }

    /**
     * Findings, answers and rows only once the run ended - while it runs, an agent's findings are not yet verified and
     * the rows not complete.
     */
    private ApiTaskRunResponse response(Long tenantId, TaskRun run) {
        List<ApiTaskRunResponse.Finding> findings = new ArrayList<>();
        List<ApiTaskRunResponse.Answer> answers = new ArrayList<>();
        List<ApiTaskRunResponse.Row> rows = new ArrayList<>();
        if (!run.state().open()) {
            for (TaskService.Row row : service.rows(tenantId, run)) {
                rows.add(new ApiTaskRunResponse.Row(row.step(), row.source(), row.values(), row.unclear()));
            }
            for (TaskService.Finding finding : service.findings(tenantId, run)) {
                findings.add(new ApiTaskRunResponse.Finding(finding.checkPoint(), finding.severity(), finding.status(),
                        finding.note(), finding.evidence().stream()
                        .map(line -> new ApiTaskRunResponse.Evidence(line.file(), line.line(), line.text())).toList()));
            }
            for (TaskAnswer answer : service.handedOn(tenantId, run)) {
                answers.add(new ApiTaskRunResponse.Answer(answer.step(), answer.choice(), answer.text()));
            }
        }
        return new ApiTaskRunResponse(run.id(), run.state().name(), run.createdAt(), run.startedAt(), run.endedAt(),
                run.errorCode(), run.errorMessage(), findings, answers, rows);
    }
}
