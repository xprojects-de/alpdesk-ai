package de.alpdesk.services.task;

import de.alpdesk.entities.task.ReadPosition;
import de.alpdesk.entities.task.RunState;
import de.alpdesk.entities.task.StepState;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.entities.task.TaskRunStep;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.repositories.task.TaskRepository;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.ai.Spent;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskDefinition.StepType;
import de.alpdesk.services.task.step.AgentStep;
import de.alpdesk.services.task.step.CallAgentStep;
import de.alpdesk.services.task.step.CallToolStep;
import de.alpdesk.services.task.step.ExtractStep;
import de.alpdesk.services.task.step.PromptStep;
import de.alpdesk.services.task.step.ReadDocumentsStep;
import de.alpdesk.services.task.step.ReadFilesStep;
import de.alpdesk.services.task.step.SendMailStep;
import de.alpdesk.services.task.step.WriteFileStep;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.context.event.ApplicationEventListener;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import io.micronaut.runtime.server.event.ServerStartupEvent;
import jakarta.inject.Named;
import jakarta.inject.Singleton;
import org.bsc.langgraph4j.CompiledGraph;
import org.bsc.langgraph4j.GraphInput;
import org.bsc.langgraph4j.RunnableConfig;
import org.bsc.langgraph4j.checkpoint.PostgresSaver;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import javax.sql.DataSource;
import java.sql.Connection;
import java.sql.SQLException;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;

/**
 * Runs a task's runs on the task executor (docs/architecture/tasks.md "Engine"): the definition the run was started
 * with as a langgraph4j graph, a checkpoint in the tenant database after every step, our own record of every step -
 * what the run view reads. At the end the read positions move on (only after success), the run's own mails go out
 * (failed, unclear) and the checkpoints are deleted. On the server's start every run still open goes on from its last
 * checkpoint. Nothing here holds a transaction: a step may wait minutes for a server or a provider.
 */
@Singleton
public class TaskRunner implements ApplicationEventListener<ServerStartupEvent> {

    private static final Logger LOG = LoggerFactory.getLogger(TaskRunner.class);

    private final TenantRepository tenants;
    private final TaskRepository tasks;
    private final TenantDatabases databases;
    private final TaskRunRepository runs;
    private final ReadFilesStep readFiles;
    private final ReadDocumentsStep readDocuments;
    private final ExtractStep extract;
    private final WriteFileStep writeFile;
    private final CallToolStep callTool;
    private final CallAgentStep callAgent;
    private final AgentStep agent;
    private final PromptStep prompt;
    private final SendMailStep sendMail;
    private final TaskMails mails;
    private final JsonMapper json;
    private final ExecutorService executor;
    /**
     * The runs submitted and not yet ended in this JVM, keyed by tenant and run - every tenant's database counts its
     * runs from 1. The server takes requests while the start's scan of open runs still goes over the tenants - a run a
     * request created meanwhile would be found and submitted a second time.
     */
    private final Set<String> submitted = ConcurrentHashMap.newKeySet();

    public TaskRunner(TenantRepository tenants, TaskRepository tasks, TenantDatabases databases, TaskRunRepository runs,
                      ReadFilesStep readFiles, ReadDocumentsStep readDocuments, ExtractStep extract,
                      WriteFileStep writeFile, CallToolStep callTool, CallAgentStep callAgent, AgentStep agent,
                      PromptStep prompt,
                      SendMailStep sendMail,
                      TaskMails mails, JsonMapper json,
                      @Named("task") ExecutorService executor) {
        this.tenants = tenants;
        this.tasks = tasks;
        this.databases = databases;
        this.runs = runs;
        this.readFiles = readFiles;
        this.readDocuments = readDocuments;
        this.extract = extract;
        this.writeFile = writeFile;
        this.callTool = callTool;
        this.callAgent = callAgent;
        this.agent = agent;
        this.prompt = prompt;
        this.sendMail = sendMail;
        this.mails = mails;
        this.json = json;
        this.executor = executor;
    }

    /** Once per run: a run already submitted is not submitted again. */
    public void submit(Long tenantId, TaskRun run) {
        if (submitted.add(key(tenantId, run.id()))) {
            executor.submit(() -> executeLogged(tenantId, run.taskId(), run.id()));
        }
    }

    /**
     * Ends a cancelled run at once when no thread of this JVM carries it out: one that broke off outside its steps
     * stays RUNNING until the next start, and its cancel would wait for a step that never comes - while it blocks every
     * start of its task. One installation, no cluster: what is not submitted here runs nowhere.
     */
    public void endIfCarriedOutByNone(Long tenantId, DataSource dataSource, Long taskId, Long runId) {
        if (submitted.contains(key(tenantId, runId))) {
            return;
        }
        if (runs.endCancelled(dataSource, taskId, runId)) {
            LOG.info("Run {} of task {} of tenant {} was cancelled - no thread carried it out", runId, taskId, tenantId);
            runs.deleteCheckpoints(dataSource, thread(runId));
        }
    }

    /**
     * What escapes a run (its tenant database out of reach when it is taken up or when its end is written) would end
     * silently in the executor's Future - the run would stay open without a word until the next start takes it up or a
     * cancel ends it.
     */
    private void executeLogged(Long tenantId, Long taskId, Long runId) {
        try {
            execute(tenantId, taskId, runId);
        } catch (RuntimeException e) {
            LOG.error("Run {} of task {} of tenant {} broke off - it stays open until the next start takes it up",
                    runId, taskId, tenantId, e);
        } finally {
            submitted.remove(key(tenantId, runId));
        }
    }

    private static String key(Long tenantId, Long runId) {
        return tenantId + ":" + runId;
    }

    /**
     * Every run still open goes on - one interrupted midway from its last checkpoint. Only the tenants with tasks are read:
     * a run exists only with its task (deleting a task deletes its runs first), and the start waits for this scan. Each
     * tenant is read on a connection of its own; its pool opens only for a run to take up.
     */
    @Override
    public void onApplicationEvent(ServerStartupEvent event) {
        Set<Long> withTasks = new HashSet<>(tasks.findTenantIdsWithTasks());
        for (Tenant tenant : tenants.findAllOrderByName()) {
            if (!withTasks.contains(tenant.id())) {
                continue;
            }
            try (Connection connection = databases.openConnection(tenant.databaseName())) {
                for (TaskRun run : runs.findOpen(connection)) {
                    LOG.info("Run {} of task {} of tenant {} goes on after the start", run.id(), run.taskId(), tenant.id());
                    submit(tenant.id(), run);
                }
            } catch (SQLException | RuntimeException e) {
                LOG.error("The open runs of tenant {} could not be read", tenant.id(), e);
            }
        }
    }

    void execute(Long tenantId, Long taskId, Long runId) {
        Tenant tenant = tenants.findById(tenantId).orElse(null);
        if (tenant == null) {
            return;
        }
        DataSource dataSource = databases.dataSource(tenant.databaseName());
        TaskRun run = runs.findById(dataSource, taskId, runId).orElse(null);
        if (run == null || !run.state().open()) {
            return;
        }
        if (!tenant.tasksAllowed() || !tenant.active()) {
            runs.finish(dataSource, runId, RunState.FAILED, "task.notAllowed", "The tenant may not run tasks");
            runs.deleteCheckpoints(dataSource, thread(runId));
            return;
        }
        if (!runs.markRunning(dataSource, runId)) {
            return;
        }
        TaskDefinition task = definition(run);
        Instant started = run.startedAt() != null ? run.startedAt() : Instant.now();
        Recorder recorder = new Recorder(tenant, dataSource, run, task, started);
        try {
            Map<String, Object> end = invoke(dataSource, task, recorder, runId);
            List<ReadPosition> positions = new ArrayList<>();
            for (Map<String, Object> result : TaskGraph.results(new TaskGraph.TaskState(end)).values()) {
                positions.addAll(ReadFilesStep.positions(result));
            }
            runs.savePositions(dataSource, taskId, earliestPerFile(positions));
            // Unclear check points end the run unclear even without an edge back - a mail on errors only would never
            // send them, and the lines are not read again
            List<Map<String, Object>> unclearChecks = unclearChecks(task, end);
            boolean unclear = TaskGraph.limitReached(task, end) || !unclearChecks.isEmpty();
            if (unclear && !mails.unclear(tenant, task, started, unclearChecks)) {
                runs.markMailFailed(dataSource, runId);
            }
            runs.finish(dataSource, runId, unclear ? RunState.UNCLEAR : RunState.DONE, null, null);
        } catch (Exception e) {
            if (runs.cancelRequested(dataSource, runId)) {
                LOG.info("Run {} of task {} of tenant {} was cancelled at {}", runId, taskId, tenantId, recorder.current);
                runs.finish(dataSource, runId, RunState.CANCELLED, null, null);
                return;
            }
            ServiceException failure = failure(e);
            // The code only: a message can quote what a connected software answered - the run and its mail keep it.
            LOG.warn("Run {} of task {} of tenant {} failed at {}: {}", runId, taskId, tenantId, recorder.current,
                    failure.code());
            runs.finish(dataSource, runId, RunState.FAILED, failure.code(), failure.getMessage());
            String step = recorder.current != null ? recorder.current : task.steps().getFirst().id();
            if (!mails.failed(tenant, task, started, step, failure.getMessage())) {
                runs.markMailFailed(dataSource, runId);
            }
        } finally {
            runs.deleteCheckpoints(dataSource, thread(runId));
        }
    }

    /** From the last checkpoint when there is one (a restart), else from the first step. */
    private Map<String, Object> invoke(DataSource dataSource, TaskDefinition task, Recorder recorder, Long runId)
            throws Exception {
        PostgresSaver saver = PostgresSaver.builder()
                .datasource(dataSource)
                .createTables(false)
                .stateSerializer(new TaskGraph.Serializer())
                .build();
        CompiledGraph<TaskGraph.TaskState> graph = TaskGraph.compile(task, recorder, saver);
        RunnableConfig config = RunnableConfig.builder().threadId(thread(runId)).build();
        Optional<TaskGraph.TaskState> end = graph.stateOf(config).isPresent()
                ? graph.invoke(GraphInput.resume(), config)
                : graph.invoke(GraphInput.args(Map.of()), config);
        return end.map(TaskGraph.TaskState::data).orElse(Map.of());
    }

    /**
     * The checks of the verify and agent steps left unclear - for the mail of a run that ended unclear - and every call of
     * a call_tool step that failed, as one more: the row waits for a person.
     */
    private static List<Map<String, Object>> unclearChecks(TaskDefinition task, Map<String, Object> end) {
        Map<String, Map<String, Object>> results = TaskGraph.results(new TaskGraph.TaskState(end));
        List<Map<String, Object>> checks = new ArrayList<>();
        for (Step step : task.steps()) {
            if ((step.type() == StepType.CALL_TOOL || step.type() == StepType.CALL_AGENT) && results.get(step.id()) != null
                    && results.get(step.id()).get(TaskResults.CALLS) instanceof List<?> calls) {
                for (Object item : calls) {
                    if (item instanceof Map<?, ?> call && Boolean.TRUE.equals(call.get("failed"))) {
                        Map<String, Object> failed = new LinkedHashMap<>();
                        failed.put("name", step.id() + ": " + call.get("source"));
                        failed.put("status", "unclear");
                        failed.put("note", String.valueOf(call.get("answer")));
                        failed.put("evidence", List.of());
                        checks.add(failed);
                    }
                }
            }
        }
        for (Step step : task.steps()) {
            if ((step.type() == StepType.VERIFY || step.type() == StepType.AGENT) && results.containsKey(step.id())
                    && isLastJudge(task, step, results)) {
                Object list = results.get(step.id()).get(TaskResults.CHECKS);
                if (list instanceof List<?> items) {
                    for (Object item : items) {
                        if (item instanceof Map<?, ?> check && "unclear".equals(check.get("status"))) {
                            @SuppressWarnings("unchecked")
                            Map<String, Object> typed = (Map<String, Object>) check;
                            checks.add(typed);
                        }
                    }
                }
            }
        }
        return checks;
    }

    /** An agent step whose findings a verify step judged is not quoted - the verification is the last word. */
    private static boolean isLastJudge(TaskDefinition task, Step step, Map<String, Map<String, Object>> results) {
        return task.steps().stream().noneMatch(other -> step.id().equals(other.verifies()) && results.containsKey(other.id()));
    }

    private TaskDefinition definition(TaskRun run) {
        try {
            return json.readValue(run.definition(), TaskDefinition.class);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
    }

    /**
     * One position per file: two read_files steps of a task reading the same file share its position. The file may
     * have grown between the two steps, so the one that read less is kept - its lines come again in the next run
     * instead of being lost for it. A read_documents step's position is the document's size - two such steps give
     * the same.
     */
    static List<ReadPosition> earliestPerFile(List<ReadPosition> positions) {
        Map<FileKey, ReadPosition> earliest = new LinkedHashMap<>();
        for (ReadPosition position : positions) {
            earliest.merge(new FileKey(position.storeId(), position.path()), position,
                    (kept, other) -> other.byteOffset() < kept.byteOffset() ? other : kept);
        }
        return new ArrayList<>(earliest.values());
    }

    private record FileKey(Long storeId, String path) {
    }

    static String thread(Long runId) {
        return "run-" + runId;
    }

    /** The failure as the run keeps it - one of ours with its code, anything else as a fault of the step. */
    private static ServiceException failure(Throwable e) {
        for (Throwable cause = e; cause != null; cause = cause.getCause()) {
            if (cause instanceof ServiceException service) {
                return service;
            }
        }
        LOG.error("A task's step failed", e);
        return new ServiceException(io.micronaut.http.HttpStatus.INTERNAL_SERVER_ERROR, "task.stepFailed",
                "The step failed: " + e.getClass().getSimpleName());
    }

    /** Runs the graph's steps and keeps our record of each - with its tokens. */
    private final class Recorder implements TaskGraph.Steps {
        private final Tenant tenant;
        private final DataSource dataSource;
        private final TaskRun run;
        private final TaskDefinition task;
        private final Instant started;
        private @Nullable String current;

        Recorder(Tenant tenant, DataSource dataSource, TaskRun run, TaskDefinition task, Instant started) {
            this.tenant = tenant;
            this.dataSource = dataSource;
            this.run = run;
            this.task = task;
            this.started = started;
        }

        @Override
        public Map<String, Object> run(Step step, int round, Map<String, Map<String, Object>> results) throws Exception {
            current = step.id();
            if (runs.cancelRequested(dataSource, run.id())) {
                throw ServiceException.conflict("task.cancelled", "The run was cancelled");
            }
            Instant begin = Instant.now();
            StepContext context = new StepContext(tenant, dataSource, run.taskId(), run.id(), task, step, round, results,
                    new Spent());
            StepOutcome outcome;
            try {
                outcome = switch (step.type()) {
                    case READ_FILES -> readFiles.run(context);
                    case READ_DOCUMENTS -> readDocuments.run(context);
                    case EXTRACT -> extract.run(context);
                    case WRITE_FILE -> writeFile.run(context);
                    case CALL_TOOL -> callTool.run(context);
                    case CALL_AGENT -> callAgent.run(context);
                    case AGENT, VERIFY -> agent.run(context);
                    case PROMPT -> prompt.run(context);
                    case SEND_MAIL -> sendMail.run(context, started);
                };
            } catch (RuntimeException e) {
                ServiceException failure = failure(e);
                // the error's values go with the step - the run view translates its code with them
                String result = failure.params().isEmpty() ? null
                        : json.writeValueAsString(Map.of("errorParams", failure.params()));
                // what its calls spent before it failed stays with it - they are in the usage too
                TaskRunStep failed = stepRow(step, round, StepState.FAILED, result, failure.code(), context.spent(),
                        begin);
                runs.saveStep(dataSource, run.id(), failed);
                runs.addSpent(dataSource, run.id(), failed);
                throw failure;
            }
            TaskRunStep done = stepRow(step, round, StepState.DONE, json.writeValueAsString(outcome.result()), null,
                    context.spent(), begin);
            runs.saveStep(dataSource, run.id(), done);
            runs.addSpent(dataSource, run.id(), done);
            if (outcome.mailFailed()) {
                runs.markMailFailed(dataSource, run.id());
            }
            return outcome.result();
        }

        @Override
        public void skipped(Step step, int round) {
            Instant now = Instant.now();
            runs.saveStep(dataSource, run.id(), stepRow(step, round, StepState.SKIPPED, null, null, new Spent(), now));
        }

        /** The step's row with what it spent - its tokens, cached ones apart, and its cost as the usage recorded it. */
        private static TaskRunStep stepRow(Step step, int round, StepState state, @Nullable String result,
                                           @Nullable String errorCode, Spent spent, Instant begin) {
            return new TaskRunStep(step.id(), round, state, result, errorCode, spent.input(), spent.output(),
                    spent.cacheRead(), spent.cacheWrite(), spent.amount(), begin, Instant.now());
        }
    }
}
