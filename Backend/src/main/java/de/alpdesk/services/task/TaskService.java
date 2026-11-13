package de.alpdesk.services.task;

import de.alpdesk.dto.task.TaskRequest;
import de.alpdesk.dto.task.TaskKeyResponse;
import de.alpdesk.dto.task.TaskResponse;
import de.alpdesk.dto.task.TaskRunResponse;
import de.alpdesk.dto.task.TaskRunStarted;
import de.alpdesk.dto.task.TaskRunSummary;
import de.alpdesk.dto.task.TaskStepView;
import de.alpdesk.dto.task.TaskSummaryResponse;
import de.alpdesk.dto.task.TaskValidationResponse;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.entities.task.RunRow;
import de.alpdesk.entities.task.RunState;
import de.alpdesk.entities.task.StartedBy;
import de.alpdesk.entities.task.StepState;
import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.entities.task.TaskRunStep;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.task.TaskRepository;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.task.TaskDefinition.Edge;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.step.StepInputs;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.transaction.TransactionOperations;
import io.micronaut.transaction.annotation.Transactional;
import jakarta.inject.Singleton;

import javax.sql.DataSource;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.sql.Connection;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/**
 * The tenant's scheduled tasks (docs/architecture/tasks.md), kept by its tenant admins and the admin: saved only when
 * their text compiles, started by hand, by their schedule (TaskScheduler), by the client software with a key the task
 * is assigned to, or by the tenant's MCP server for a task opened to it - with their runs in the tenant database.
 * Whether a tenant may have tasks at all is the admin's switch: without it a tenant admin gets 409 everywhere, the admin
 * may still prepare a task - but nobody starts one.
 */
@Singleton
public class TaskService {

    static final int MAX_RUNS_PAGE = 200;

    private final TaskRepository tasks;
    private final TenantRepository tenants;
    private final TaskCompiler compiler;
    private final TenantDatabases databases;
    private final TaskRunRepository runs;
    private final TaskRunner runner;
    private final RunCosts costs;
    private final JsonMapper json;
    private final TransactionOperations<Connection> transactions;
    private final ZoneId zone;

    public TaskService(TaskRepository tasks, TenantRepository tenants, TaskCompiler compiler,
                       TenantDatabases databases, TaskRunRepository runs, TaskRunner runner, RunCosts costs,
                       JsonMapper json, TransactionOperations<Connection> transactions,
                       @Value("${app.timezone}") String zone) {
        this.zone = ZoneId.of(zone);
        this.tasks = tasks;
        this.tenants = tenants;
        this.compiler = compiler;
        this.databases = databases;
        this.runs = runs;
        this.runner = runner;
        this.costs = costs;
        this.json = json;
        this.transactions = transactions;
    }

    public List<TaskSummaryResponse> list(Long tenantId, StartedBy caller) {
        Tenant tenant = tenant(tenantId, caller);
        DataSource dataSource = dataSource(tenant);
        List<TaskSummaryResponse> list = new ArrayList<>();
        for (Task task : tasks.findByTenantIdOrderByName(tenantId)) {
            list.add(new TaskSummaryResponse(task.id(), task.name(), task.active(), task.version(),
                    triggers(read(task.definition())), task.nextRunAt(),
                    runs.findLast(dataSource, task.id()).map(TaskService::summary).orElse(null), task.createdAt(),
                    task.updatedAt()));
        }
        return list;
    }

    public Optional<TaskResponse> get(Long tenantId, StartedBy caller, Long taskId) {
        Tenant tenant = tenant(tenantId, caller);
        return tasks.findByIdAndTenantId(taskId, tenantId).map(task -> response(task, dataSource(tenant)));
    }

    /** Checks a text as saving would - nothing is stored. */
    public TaskValidationResponse validate(Long tenantId, StartedBy caller, @Nullable String source) {
        tenant(tenantId, caller);
        TaskCompiler.Compiled compiled = compiler.compile(tenantId, source);
        return new TaskValidationResponse(compiled.valid(), compiled.errors(),
                compiled.valid() ? views(compiled.definition()) : List.of());
    }

    /** 400 with the first mistake when the text does not compile (the check lists them all), 409 for a name taken. */
    @Transactional
    public TaskResponse create(Long tenantId, StartedBy caller, TaskRequest request) {
        Tenant tenant = tenant(tenantId, caller);
        TaskDefinition definition = compile(tenantId, request.source());
        requireFreeName(tenantId, definition.name(), null);
        requireFreeFiles(tenantId, definition, null);
        Instant now = Instant.now();
        boolean active = request.active() == null || request.active();
        Task task = tasks.save(new Task(null, tenantId, definition.name(), request.source(), write(definition), 1,
                active, nextRun(definition, active, now), now, now));
        writeStores(task.id(), definition);
        return response(task, dataSource(tenant));
    }

    @Transactional
    public Optional<TaskResponse> update(Long tenantId, StartedBy caller, Long taskId, TaskRequest request) {
        Tenant tenant = tenant(tenantId, caller);
        Optional<Task> existing = tasks.findByIdAndTenantId(taskId, tenantId);
        if (existing.isEmpty()) {
            return Optional.empty();
        }
        Task task = existing.get();
        TaskDefinition definition = compile(tenantId, request.source());
        requireFreeName(tenantId, definition.name(), taskId);
        requireFreeFiles(tenantId, definition, taskId);
        boolean active = request.active() != null ? request.active() : task.active();
        Instant now = Instant.now();
        Task saved = tasks.update(new Task(taskId, tenantId, definition.name(), request.source(), write(definition),
                task.version() + 1, active, nextRun(definition, active, now), task.createdAt(), now));
        writeStores(taskId, definition);
        return Optional.of(response(saved, dataSource(tenant)));
    }

    /**
     * Deletes the task with its runs and read positions in the tenant database first (no foreign key across the two
     * databases), then the task. 409 while a run of it is open. The task is locked first: a run being started at this
     * moment is either created before (409) or finds the task gone - never a run left behind by a deleted task.
     */
    @Transactional
    public boolean delete(Long tenantId, StartedBy caller, Long taskId) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantIdForUpdate(taskId, tenantId).isEmpty()) {
            return false;
        }
        DataSource dataSource = dataSource(tenant);
        if (runs.findLast(dataSource, taskId).filter(run -> run.state().open()).isPresent()) {
            throw ServiceException.conflict("task.running", "The task is running - wait for its end");
        }
        runs.deleteTask(dataSource, taskId);
        return tasks.deleteByIdAndTenantId(taskId, tenantId) > 0;
    }

    /**
     * Starts a run now, in the background. 409 when the tenant may not run tasks, the task is switched off or a run of
     * it is still open - one run of a task at a time.
     */
    public Optional<TaskRunStarted> start(Long tenantId, StartedBy caller, Long taskId, @Nullable Long userId) {
        Tenant tenant = tenant(tenantId, caller);
        return tasks.findByIdAndTenantId(taskId, tenantId)
                .map(task -> new TaskRunStarted(startRun(tenant, task, caller, userId, null).id()));
    }

    /** The client software's start: only a task assigned to its key - any other answers empty (404). */
    public Optional<TaskRunStarted> startByApiKey(Long tenantId, Long apiKeyId, Long taskId) {
        Tenant tenant = tenant(tenantId, StartedBy.API);
        return keyTask(tenantId, apiKeyId, taskId)
                .map(task -> new TaskRunStarted(startRun(tenant, task, StartedBy.API, null, apiKeyId).id()));
    }

    /**
     * Starts a run of the task. 409 when the tenant may not run tasks, the task is switched off or a run of it is still
     * open - one run of a task at a time. The task stays locked against its deletion while its run is created (see
     * delete); 404 when it was deleted meanwhile.
     */
    TaskRun startRun(Tenant tenant, Task task, StartedBy startedBy, @Nullable Long userId, @Nullable Long apiKeyId) {
        if (!tenant.tasksAllowed() || !tenant.active()) {
            throw notAllowed();
        }
        TaskRun run = transactions.executeWrite(_ -> {
            Task current = tasks.findByIdForKeyShare(task.id())
                    .orElseThrow(() -> ServiceException.notFound("task.notFound", "The task was deleted"));
            if (!current.active()) {
                throw ServiceException.conflict("task.inactive", "The task is switched off");
            }
            return runs.create(dataSource(tenant), current.id(), current.version(), current.definition(), startedBy,
                            userId, apiKeyId)
                    .orElseThrow(() -> ServiceException.conflict("task.running", "A run of the task is still open"));
        });
        runner.submit(tenant.id(), run);
        return run;
    }

    /**
     * Cancels a run: one still waiting at once, a running one before its next step or round of its model - or at once
     * when no thread carries it out any more (broken off outside its steps). 404 for an unknown run, 409 for one that
     * has ended.
     */
    public boolean cancel(Long tenantId, StartedBy caller, Long taskId, Long runId) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantId(taskId, tenantId).isEmpty()) {
            return false;
        }
        DataSource dataSource = dataSource(tenant);
        if (runs.findById(dataSource, taskId, runId).isEmpty()) {
            return false;
        }
        if (!runs.cancel(dataSource, taskId, runId)) {
            throw ServiceException.conflict("task.runEnded", "The run has ended already");
        }
        runner.endIfCarriedOutByNone(tenant.id(), dataSource, taskId, runId);
        return true;
    }

    /** The tasks assigned to the key, in the order of their names. */
    public List<Task> tasksOfApiKey(Long tenantId, Long apiKeyId) {
        Set<Long> assigned = new LinkedHashSet<>(tasks.findTaskIdsOfApiKey(apiKeyId));
        return tasks.findByTenantIdOrderByName(tenantId).stream().filter(task -> assigned.contains(task.id())).toList();
    }

    public List<Long> taskIdsOfApiKey(Long apiKeyId) {
        return tasks.findTaskIdsOfApiKey(apiKeyId);
    }

    /** Replaces the tasks the key may start; null: unchanged. 400 for a task of another tenant or none. */
    @Transactional
    public void assignApiKey(Long tenantId, Long apiKeyId, @Nullable List<Long> taskIds) {
        if (taskIds == null) {
            return;
        }
        Set<Long> known = new LinkedHashSet<>();
        tasks.findByTenantIdOrderByName(tenantId).forEach(task -> known.add(task.id()));
        for (Long taskId : taskIds) {
            if (taskId == null || !known.contains(taskId)) {
                throw ServiceException.badRequest("apiKey.taskUnknown", "Unknown task " + taskId,
                        Map.of("id", String.valueOf(taskId)));
            }
        }
        tasks.unassignApiKey(apiKeyId);
        new LinkedHashSet<>(taskIds).forEach(taskId -> tasks.assignApiKey(apiKeyId, taskId));
    }

    /** The task, when it is the tenant's and assigned to the key. */
    public Optional<Task> keyTask(Long tenantId, Long apiKeyId, Long taskId) {
        return tasks.findByIdAndTenantId(taskId, tenantId)
                .filter(task -> tasks.findTaskIdsOfApiKey(apiKeyId).contains(task.id()));
    }

    /** The tasks assigned to the MCP key, in the order of their names. */
    public List<Task> tasksOfMcpKey(Long tenantId, Long mcpKeyId) {
        Set<Long> assigned = new LinkedHashSet<>(tasks.findTaskIdsOfMcpKey(mcpKeyId));
        return tasks.findByTenantIdOrderByName(tenantId).stream().filter(task -> assigned.contains(task.id())).toList();
    }

    /** The task assigned to the MCP key of this name (case does not matter). */
    public Optional<Task> mcpTask(Long tenantId, Long mcpKeyId, String name) {
        return tasksOfMcpKey(tenantId, mcpKeyId).stream().filter(task -> task.name().equalsIgnoreCase(name.strip()))
                .findFirst();
    }

    public List<Long> taskIdsOfMcpKey(Long mcpKeyId) {
        return tasks.findTaskIdsOfMcpKey(mcpKeyId);
    }

    /** The tasks of every MCP key of the tenant, by key - for the list of keys. */
    public Map<Long, List<Long>> tasksOfMcpKeys(Long tenantId) {
        Map<Long, List<Long>> byKey = new HashMap<>();
        for (TaskRepository.McpKeyTaskRow row : tasks.findMcpKeyTasksOfTenant(tenantId)) {
            byKey.computeIfAbsent(row.mcpKeyId(), _ -> new ArrayList<>()).add(row.taskId());
        }
        return byKey;
    }

    /**
     * Replaces the tasks the MCP key may list and start - in the caller's transaction, which holds the key locked;
     * null: unchanged. 400 for a task of another tenant or none.
     */
    public void assignMcpKey(Long tenantId, Long mcpKeyId, @Nullable List<Long> taskIds) {
        if (taskIds == null) {
            return;
        }
        Set<Long> known = new LinkedHashSet<>();
        tasks.findByTenantIdOrderByName(tenantId).forEach(task -> known.add(task.id()));
        for (Long taskId : taskIds) {
            if (taskId == null || !known.contains(taskId)) {
                throw ServiceException.badRequest("mcpKey.unknownTask", "Unknown task " + taskId,
                        Map.of("id", String.valueOf(taskId)));
            }
        }
        tasks.unassignMcpKey(mcpKeyId);
        new LinkedHashSet<>(taskIds).forEach(taskId -> tasks.assignMcpKey(mcpKeyId, taskId));
    }

    public TaskRun startByMcp(Long tenantId, Task task) {
        return startRun(tenant(tenantId, StartedBy.MCP), task, StartedBy.MCP, null, null);
    }

    /** A run started over the tenant's A2A server (docs/architecture/a2a.md "Tasks over A2A"). */
    public TaskRun startByA2a(Long tenantId, Task task) {
        return startRun(tenant(tenantId, StartedBy.A2A), task, StartedBy.A2A, null, null);
    }

    /** A run of a task of the tenant, without the tenant admin's switch check - for the API and MCP answers. */
    public Optional<TaskRun> findRun(Long tenantId, Long taskId, Long runId) {
        Tenant tenant = tenant(tenantId, StartedBy.API);
        return runs.findById(dataSource(tenant), taskId, runId);
    }

    /** The tenant's task of this id - for the A2A server, which knows it from the run it started. */
    public Optional<Task> taskOfTenant(Long tenantId, Long taskId) {
        return tasks.findByIdAndTenantId(taskId, tenantId);
    }

    public Optional<TaskRun> lastRun(Long tenantId, Long taskId) {
        return runs.findLast(dataSource(tenant(tenantId, StartedBy.API)), taskId);
    }

    /**
     * What a run found, for the client software and MCP: the check points of its last judging steps (an agent step a
     * verify step judged again is not quoted, nor one it was still to judge; a step whose last round failed neither)
     * that are not ok - with their evidence lines, without the lines read.
     */
    public List<Finding> findings(Long tenantId, TaskRun run) {
        TaskDefinition definition = read(run.definition());
        Map<String, Map<String, Object>> latest = new LinkedHashMap<>();
        Map<String, StepState> lastState = new HashMap<>();
        for (TaskRunStep step : runs.findSteps(dataSource(tenant(tenantId, StartedBy.API)), run.id())) {
            if (step.result() != null) {
                latest.put(step.stepId(), readMap(step.result()));
            }
            lastState.put(step.stepId(), step.state());
        }
        boolean ranThrough = run.state() == RunState.DONE || run.state() == RunState.UNCLEAR;
        List<Finding> findings = new ArrayList<>();
        for (Step step : definition.steps()) {
            boolean judges = step.type() == TaskDefinition.StepType.AGENT || step.type() == TaskDefinition.StepType.VERIFY;
            // a judging step whose last round failed has no result of this run - an earlier round's is stale
            if (!judges || lastState.get(step.id()) != StepState.DONE || !leftToItself(step, definition, lastState, ranThrough)
                    || !(latest.get(step.id()) instanceof Map<String, Object> result)
                    || !(result.get(TaskResults.CHECKS) instanceof List<?> checks)) {
                continue;
            }
            for (Object item : checks) {
                if (item instanceof Map<?, ?> check && !"ok".equals(check.get("status"))) {
                    findings.add(new Finding(step.id(), String.valueOf(check.get("name")),
                            String.valueOf(check.get("severity")), String.valueOf(check.get("status")),
                            String.valueOf(check.get("note")), evidence(check.get("evidence"))));
                }
            }
        }
        return findings;
    }

    /**
     * Whether no verify step judges this step's findings: none does, its condition skipped it, or the run ran through
     * without reaching it. A verify that judged them is quoted instead; one that failed or was not reached in a run that
     * broke off leaves them unverified - not to be handed on as findings.
     */
    private static boolean leftToItself(Step step, TaskDefinition definition, Map<String, StepState> lastState,
                                        boolean ranThrough) {
        for (Step other : definition.steps()) {
            if (!step.id().equals(other.verifies())) {
                continue;
            }
            StepState state = lastState.get(other.id());
            if (state == null ? !ranThrough : state != StepState.SKIPPED) {
                return false;
            }
        }
        return true;
    }

    /**
     * What a run hands on, for the client software and MCP: the last answer of every prompt step a send_mail step takes
     * as its input.
     */
    public List<TaskAnswer> handedOn(Long tenantId, TaskRun run) {
        TaskDefinition definition = read(run.definition());
        Set<String> mailed = new LinkedHashSet<>();
        for (Step step : definition.steps()) {
            if (step.type() == TaskDefinition.StepType.SEND_MAIL) {
                mailed.addAll(step.input());
            }
        }
        Map<String, TaskAnswer> last = new LinkedHashMap<>();
        for (TaskAnswer answer : answers(dataSource(tenant(tenantId, StartedBy.API)), run)) {
            if (mailed.contains(answer.step())) {
                last.put(answer.step(), answer);
            }
        }
        return List.copyOf(last.values());
    }

    /** Every answer of the run's prompt steps, round by round - from the run files they stored. */
    private List<TaskAnswer> answers(DataSource dataSource, TaskRun run) {
        TaskDefinition definition = read(run.definition());
        List<TaskAnswer> answers = new ArrayList<>();
        for (TaskRunStep step : runs.findSteps(dataSource, run.id())) {
            boolean prompt = definition.steps().stream()
                    .anyMatch(s -> s.id().equals(step.stepId()) && s.type() == TaskDefinition.StepType.PROMPT);
            if (!prompt || step.result() == null) {
                continue;
            }
            Map<String, Object> result = readMap(step.result());
            List<Long> ids = new ArrayList<>();
            if (result.get(TaskResults.FILE_IDS) instanceof List<?> fileIds) {
                fileIds.forEach(id -> ids.add(((Number) id).longValue()));
            }
            for (RunFile file : runs.findFiles(dataSource, run.id(), ids)) {
                answers.add(new TaskAnswer(step.stepId(), step.round(), (String) result.get(TaskResults.CHOICE),
                        StepInputs.text(file)));
            }
        }
        return answers;
    }

    /** A check point a run did not find ok. */
    /** A row an extract step of a run gave - for the run view, the client software and MCP. */
    public record Row(String step, String source, Map<String, Object> values, @Nullable String unclear) {
    }

    /** Every row the run's extract steps gave, in their order. */
    public List<Row> rows(Long tenantId, TaskRun run) {
        return rows(dataSource(tenant(tenantId, StartedBy.API)), run);
    }

    private List<Row> rows(DataSource dataSource, TaskRun run) {
        List<Row> rows = new ArrayList<>();
        for (RunRow row : runs.findRows(dataSource, run.id())) {
            rows.add(new Row(row.stepId(), row.source(), readMap(row.values()), row.unclear()));
        }
        return rows;
    }

    public record Finding(String step, String checkPoint, String severity, String status, String note,
                          List<Evidence> evidence) {
    }

    public record Evidence(String file, long line, String text) {
    }

    private static List<Evidence> evidence(Object value) {
        List<Evidence> evidence = new ArrayList<>();
        if (value instanceof List<?> list) {
            for (Object item : list) {
                if (item instanceof Map<?, ?> line && line.get("line") instanceof Number number) {
                    evidence.add(new Evidence(String.valueOf(line.get("file")), number.longValue(),
                            String.valueOf(line.get("text"))));
                }
            }
        }
        return evidence;
    }

    /** When the schedule is due first after a save: from now; none for a task switched off or without triggers. */
    private @Nullable Instant nextRun(TaskDefinition definition, boolean active, Instant now) {
        return active ? TaskSchedule.nextAfter(definition.triggers(), now, zone) : null;
    }

    /** The triggers as the list shows them. */
    static List<String> triggers(TaskDefinition definition) {
        List<String> triggers = new ArrayList<>();
        for (TaskDefinition.Trigger trigger : definition.triggers()) {
            triggers.add(trigger.everyMinutes() != null ? "every " + every(trigger.everyMinutes()) : "cron " + trigger.cron());
        }
        return triggers;
    }

    private static String every(int minutes) {
        if (minutes % (24 * 60) == 0) {
            return minutes / (24 * 60) + "d";
        }
        return minutes % 60 == 0 ? minutes / 60 + "h" : minutes + "m";
    }

    /** Newest first; empty for an unknown task. */
    public Optional<List<TaskRunSummary>> runs(Long tenantId, StartedBy caller, Long taskId, int limit, int offset) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantId(taskId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        int page = Math.clamp(limit, 1, MAX_RUNS_PAGE);
        DataSource dataSource = dataSource(tenant);
        List<TaskRun> found = runs.findByTask(dataSource, taskId, page, Math.max(0, offset));
        return Optional.of(withCosts(dataSource, found));
    }

    /**
     * The page's runs with what each cost: kept with the run since costs are kept; a run from before is computed from
     * its steps' tokens - their tokens and models read once for those of the page.
     */
    private List<TaskRunSummary> withCosts(DataSource dataSource, List<TaskRun> page) {
        Map<Long, TaskDefinition> definitions = new HashMap<>();
        Map<Long, RunCosts.Sum> sums = new HashMap<>();
        for (TaskRun run : page) {
            if (run.cost() == null) {
                definitions.put(run.id(), read(run.definition()));
                sums.put(run.id(), new RunCosts.Sum());
            }
        }
        if (definitions.isEmpty()) {
            return page.stream().map(TaskService::summary).toList();
        }
        RunCosts.Prices prices = costs.prices(definitions.values());
        for (TaskRunRepository.StepTokens step : runs.findStepTokens(dataSource, List.copyOf(definitions.keySet()))) {
            BigDecimal cost = prices.of(definitions.get(step.runId()), step.stepId(), step.inputTokens(),
                    step.outputTokens());
            sums.get(step.runId()).add(cost, step.inputTokens(), step.outputTokens());
        }
        List<TaskRunSummary> summaries = new ArrayList<>();
        for (TaskRun run : page) {
            summaries.add(run.cost() != null ? summary(run) : summary(run, sums.get(run.id())));
        }
        return summaries;
    }

    public Optional<TaskRunResponse> run(Long tenantId, StartedBy caller, Long taskId, Long runId) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantId(taskId, tenantId).isEmpty()) {
            return Optional.empty();
        }
        DataSource dataSource = dataSource(tenant);
        return runs.findById(dataSource, taskId, runId).map(run -> {
            TaskDefinition definition = read(run.definition());
            // only a run from before costs were kept needs the catalog's prices
            RunCosts.Prices prices = run.cost() == null ? costs.prices(List.of(definition)) : null;
            RunCosts.Sum sum = new RunCosts.Sum();
            List<TaskRunResponse.Step> steps = new ArrayList<>();
            for (TaskRunStep step : runs.findSteps(dataSource, runId)) {
                BigDecimal cost = prices == null || step.cost() != null ? nullIfNothing(step)
                        : prices.of(definition, step.stepId(), step.inputTokens(), step.outputTokens());
                sum.add(cost, step.inputTokens(), step.outputTokens());
                steps.add(new TaskRunResponse.Step(step.stepId(), step.round(), step.state().name(),
                        step.result() == null ? null : readMap(step.result()), step.errorCode(), step.inputTokens(),
                        step.outputTokens(), step.cacheReadTokens(), step.cacheWriteTokens(), step.startedAt(),
                        step.endedAt(), cost));
            }
            List<TaskRunResponse.Answer> answers = new ArrayList<>();
            for (TaskAnswer answer : answers(dataSource, run)) {
                answers.add(new TaskRunResponse.Answer(answer.step(), answer.round(), answer.choice(), answer.text()));
            }
            List<TaskRunResponse.Row> rows = new ArrayList<>();
            for (Row row : rows(dataSource, run)) {
                rows.add(new TaskRunResponse.Row(row.step(), row.source(), row.values(), row.unclear()));
            }
            return new TaskRunResponse(run.cost() != null ? summary(run) : summary(run, sum), steps, views(definition),
                    answers, rows);
        });
    }

    /** 409 for a run still open. */
    public boolean deleteRun(Long tenantId, StartedBy caller, Long taskId, Long runId) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantId(taskId, tenantId).isEmpty()) {
            return false;
        }
        DataSource dataSource = dataSource(tenant);
        Optional<TaskRun> run = runs.findById(dataSource, taskId, runId);
        if (run.isEmpty()) {
            return false;
        }
        if (!runs.delete(dataSource, taskId, runId)) {
            throw ServiceException.conflict("task.runOpen", "The run is still open - wait for its end");
        }
        return true;
    }

    /** Every ended run of the task - one still open stays. */
    public boolean deleteRuns(Long tenantId, StartedBy caller, Long taskId) {
        Tenant tenant = tenant(tenantId, caller);
        if (tasks.findByIdAndTenantId(taskId, tenantId).isEmpty()) {
            return false;
        }
        runs.deleteEnded(dataSource(tenant), taskId);
        return true;
    }

    private TaskDefinition compile(Long tenantId, @Nullable String source) {
        TaskCompiler.Compiled compiled = compiler.compile(tenantId, source);
        if (!compiled.valid()) {
            TaskError first = compiled.errors().getFirst();
            String where = first.line() > 0 ? " (line " + first.line() + ")" : "";
            throw ServiceException.badRequest("task.invalid", "The task is not valid: " + first.message() + where,
                    Map.of("line", String.valueOf(first.line()), "message", first.message(),
                            "count", String.valueOf(compiled.errors().size())));
        }
        return compiled.definition();
    }

    private void requireFreeName(Long tenantId, String name, @Nullable Long taskId) {
        boolean taken = tasks.findByTenantIdOrderByName(tenantId).stream()
                .anyMatch(other -> other.name().equalsIgnoreCase(name) && !other.id().equals(taskId));
        if (taken) {
            throw ServiceException.conflict("task.nameTaken", "The tenant has a task of this name", Map.of("name", name));
        }
    }

    /**
     * 409 when another task of the tenant writes a file of the same store with the same name (its placeholders compared
     * as written): on S3 and SharePoint a file is replaced whole, and two tasks would each drop the other's rows.
     */
    private void requireFreeFiles(Long tenantId, TaskDefinition definition, @Nullable Long taskId) {
        Set<String> files = writtenFiles(definition);
        if (files.isEmpty()) {
            return;
        }
        for (Task other : tasks.findByTenantIdOrderByName(tenantId)) {
            if (other.id().equals(taskId)) {
                continue;
            }
            for (String file : writtenFiles(read(other.definition()))) {
                if (files.contains(file)) {
                    String name = file.substring(file.indexOf(':') + 1);
                    throw ServiceException.conflict("task.fileTaken", "The task " + other.name() + " writes the file "
                            + name + " already - give this one a file of its own", Map.of("task", other.name(),
                            "file", name));
                }
            }
        }
    }

    /** "<store id>:<file>" of every write_file step. */
    private static Set<String> writtenFiles(TaskDefinition definition) {
        Set<String> files = new LinkedHashSet<>();
        for (Step step : definition.steps()) {
            if (step.writeFile() != null) {
                files.add(step.writeFile().storeId() + ":" + step.writeFile().file());
            }
        }
        return files;
    }

    /** The stores the task uses - a store in use cannot be deleted. */
    private void writeStores(Long taskId, TaskDefinition definition) {
        tasks.deleteStores(taskId);
        Set<Long> storeIds = new LinkedHashSet<>();
        for (Step step : definition.steps()) {
            if (step.readFiles() != null) {
                storeIds.add(step.readFiles().storeId());
            }
            if (step.readDocuments() != null) {
                storeIds.add(step.readDocuments().storeId());
            }
            if (step.writeFile() != null) {
                storeIds.add(step.writeFile().storeId());
            }
        }
        storeIds.forEach(storeId -> tasks.addStore(taskId, storeId));
    }

    /** 404 for an unknown tenant; 409 for a tenant admin whose tenant may not have tasks (the others are checked at the start). */
    Tenant tenant(Long tenantId, StartedBy caller) {
        Tenant tenant = tenants.findById(tenantId)
                .orElseThrow(() -> ServiceException.notFound("tenant.notFound", "Tenant not found"));
        if (caller == StartedBy.TENANT_ADMIN && !tenant.tasksAllowed()) {
            throw notAllowed();
        }
        return tenant;
    }

    private static ServiceException notAllowed() {
        return ServiceException.conflict("task.notAllowed", "Tasks are not enabled for this tenant - the admin decides");
    }

    private DataSource dataSource(Tenant tenant) {
        return databases.dataSource(tenant.databaseName());
    }

    private TaskResponse response(Task task, DataSource dataSource) {
        TaskDefinition definition = read(task.definition());
        return new TaskResponse(task.id(), task.name(), task.active(), keysOf(task.id()), task.version(), task.source(),
                triggers(definition), task.nextRunAt(), views(definition),
                runs.findLast(dataSource, task.id()).map(TaskService::summary).orElse(null), task.createdAt(),
                task.updatedAt());
    }

    private List<TaskKeyResponse> keysOf(Long taskId) {
        List<TaskKeyResponse> keys = new ArrayList<>();
        for (TaskRepository.AssignedKeyRow row : tasks.findKeysOfTask(taskId)) {
            keys.add(new TaskKeyResponse(row.kind(), row.name()));
        }
        return keys;
    }

    /** With the cost the run kept - null for a run from before (the task list does not compute it). */
    static TaskRunSummary summary(TaskRun run) {
        return summary(run, run.cost(), false);
    }

    /** A run from before costs were kept, its cost computed from its steps' tokens. */
    private static TaskRunSummary summary(TaskRun run, RunCosts.Sum computed) {
        return summary(run, computed.amount(), computed.partial());
    }

    private static TaskRunSummary summary(TaskRun run, @Nullable BigDecimal cost, boolean partial) {
        return new TaskRunSummary(run.id(), run.taskVersion(), run.state().name(), run.startedBy().name(),
                run.startedByUser(), run.startedByKey(), run.cancelRequested(), run.errorCode(), run.errorMessage(), run.mailFailed(), run.inputTokens(),
                run.outputTokens(), run.cacheReadTokens(), run.cacheWriteTokens(), run.createdAt(), run.startedAt(),
                run.endedAt(), cost, partial);
    }

    /** A kept cost of a step that called no model is shown as none, like one computed. */
    @Nullable
    private static BigDecimal nullIfNothing(TaskRunStep step) {
        boolean called = step.inputTokens() + step.outputTokens() + step.cacheReadTokens() + step.cacheWriteTokens() > 0;
        return called ? step.cost() : null;
    }

    static List<TaskStepView> views(TaskDefinition definition) {
        List<TaskStepView> views = new ArrayList<>();
        for (Step step : definition.steps()) {
            List<TaskStepView.Edge> edges = new ArrayList<>();
            for (Edge edge : step.next()) {
                edges.add(new TaskStepView.Edge(edge.when() == null ? null : edge.when().text(), edge.to(),
                        edge.maxRounds()));
            }
            views.add(new TaskStepView(step.id(), step.type().written(), step.line(), step.input(),
                    step.when() == null ? null : step.when().text(), edges, details(step)));
        }
        return views;
    }

    private static Map<String, String> details(Step step) {
        return switch (step.type()) {
            case READ_FILES -> Map.of("store", step.readFiles().store(), "pattern", step.readFiles().pattern(),
                    "onlyNew", String.valueOf(step.readFiles().onlyNew()));
            case CALL_TOOL -> Map.of("connection", step.callTool().connection(), "tool", step.callTool().tool(),
                    "each", step.callTool().all() ? "all" : "row");
            case CALL_AGENT -> Map.of("agent", step.callAgent().agent(), "skill",
                    step.callAgent().skill() == null ? "" : step.callAgent().skill(),
                    "each", step.callAgent().all() ? "all" : "row");
            case WRITE_FILE -> Map.of("store", step.writeFile().store(), "file", step.writeFile().file(),
                    "mode", step.writeFile().replace() ? "replace" : "append");
            case EXTRACT -> Map.of("model", step.agent().model(), "fields",
                    String.join(", ", step.extract().fields().stream().map(TaskDefinition.Field::name).toList()),
                    "knowledge", String.valueOf(step.extract().knowledge()));
            case READ_DOCUMENTS -> Map.of("store", step.readDocuments().store(),
                    "pattern", String.join(", ", step.readDocuments().patterns()),
                    "subfolders", String.valueOf(step.readDocuments().subfolders()));
            case AGENT -> Map.of("model", step.agent().model(), "checks", String.valueOf(step.agent().checks().size()));
            case VERIFY -> Map.of("of", step.verifies());
            case PROMPT -> Map.of("model", step.agent().model() == null ? "" : step.agent().model(),
                    "choices", String.join(", ", step.choices()));
            case SEND_MAIL -> Map.of("to", String.join(", ", step.sendMail().to()));
        };
    }

    private String write(TaskDefinition definition) {
        try {
            return json.writeValueAsString(definition);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /** What the task is for, as its text says - for whoever may start it from outside; null: none written. */
    public @Nullable String description(Task task) {
        return read(task.definition()).description();
    }

    private TaskDefinition read(String definition) {
        try {
            return json.readValue(definition, TaskDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> readMap(String value) {
        try {
            return json.readValue(value, Map.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }
}
