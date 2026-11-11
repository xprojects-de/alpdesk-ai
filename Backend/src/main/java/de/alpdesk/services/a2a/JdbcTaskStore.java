package de.alpdesk.services.a2a;

import de.alpdesk.entities.a2a.A2aTaskIndex;
import de.alpdesk.entities.tenant.Tenant;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.repositories.a2a.A2aTaskIndexRepository;
import de.alpdesk.repositories.a2a.A2aTaskRepository;
import de.alpdesk.repositories.tenant.TenantRepository;
import de.alpdesk.services.task.TaskService;
import de.alpdesk.services.tenant.TenantDatabases;
import io.micronaut.core.annotation.Nullable;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.jsonrpc.common.json.JsonProcessingException;
import org.a2aproject.sdk.jsonrpc.common.json.JsonUtil;
import org.a2aproject.sdk.jsonrpc.common.wrappers.ListTasksResult;
import org.a2aproject.sdk.server.ServerCallContext;
import org.a2aproject.sdk.server.tasks.TaskStateProvider;
import org.a2aproject.sdk.server.tasks.TaskStore;
import org.a2aproject.sdk.spec.ListTasksParams;
import org.a2aproject.sdk.spec.Task;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;

import javax.sql.DataSource;
import java.lang.management.ManagementFactory;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * The SDK's task store on the tenants' databases (docs/architecture/a2a.md "Tasks"): a task kept as the SDK's JSON in
 * the a2a_task table of its tenant, found through the management database's index (the SDK asks by id alone). A task
 * belongs to the MCP key that started it - the list is the caller's key's ({@link KeyOwnsTask} guards the rest).
 * <p>
 * A run of a scheduled task is mirrored while its starter waits for it; when nobody waits any more (a restart, or the
 * wait's end) the task is read from the run itself. A question still working from before this JVM's start was lost
 * with it: answered failed "interrupted by a restart" and written so.
 */
@Singleton
public class JdbcTaskStore implements TaskStore, TaskStateProvider {

    static final String RESTARTED = "interrupted by a restart";
    private static final Instant STARTED = Instant.ofEpochMilli(ManagementFactory.getRuntimeMXBean().getStartTime());

    private final A2aTaskIndexRepository index;
    private final A2aTaskRepository rows;
    private final TenantRepository tenants;
    private final TenantDatabases databases;
    private final TaskService taskService;
    private final RunTasks runTasks;
    /**
     * Tasks the SDK stored before it recorded their owner - it records a blocking call's owner only when it answers:
     * written as soon as the owner is known.
     */
    private final Map<String, Task> unowned = new ConcurrentHashMap<>();

    public JdbcTaskStore(A2aTaskIndexRepository index, A2aTaskRepository rows, TenantRepository tenants,
                         TenantDatabases databases, TaskService taskService, RunTasks runTasks) {
        this.index = index;
        this.rows = rows;
        this.tenants = tenants;
        this.databases = databases;
        this.taskService = taskService;
        this.runTasks = runTasks;
    }

    /** Called by the key's authorization when a task is created - before the SDK stores it. */
    void own(String taskId, Long tenantId, Long mcpKeyId) {
        if (!index.existsById(taskId)) {
            index.save(new A2aTaskIndex(taskId, tenantId, mcpKeyId, Instant.now()));
        }
        Task early = unowned.remove(taskId);
        // Only when nothing newer was written meanwhile.
        if (early != null && rows.find(dataSource(tenantId), taskId).isEmpty()) {
            save(early, false);
        }
    }

    Optional<A2aTaskIndex> owner(String taskId) {
        return index.findById(taskId);
    }

    @Override
    public void save(Task task, boolean isReplicated) {
        A2aTaskIndex owner = index.findById(task.id()).orElse(null);
        if (owner == null) {
            unowned.put(task.id(), task);
            return;
        }
        unowned.remove(task.id());
        rows.save(dataSource(owner.tenantId()), task.id(), owner.mcpKeyId(), task.status().state().name(),
                task.contextId(), runId(task), json(task));
    }

    @Override
    public @Nullable Task get(String taskId) {
        A2aTaskIndex owner = index.findById(taskId).orElse(null);
        if (owner == null) {
            // The SDK builds every event onto the task as it stored it last - also before it knows the owner.
            return unowned.get(taskId);
        }
        DataSource dataSource = dataSource(owner.tenantId());
        return rows.find(dataSource, taskId).map(row -> current(owner.tenantId(), dataSource, row)).orElse(null);
    }

    @Override
    public void delete(String taskId) {
        index.findById(taskId).ifPresent(owner -> {
            rows.delete(dataSource(owner.tenantId()), taskId);
            index.deleteById(taskId);
        });
    }

    /** The caller's key's tasks, the last changed first, a page of them - the offset is the page token. */
    @Override
    public ListTasksResult list(ListTasksParams params, @Nullable ServerCallContext context) {
        Long tenantId = context == null ? null : (Long) context.getState().get(A2aAgentCore.TENANT_ID);
        Long keyId = context == null ? null : (Long) context.getState().get(A2aAgentCore.MCP_KEY_ID);
        if (tenantId == null || keyId == null) {
            return new ListTasksResult(List.of(), 0, 0, "");
        }
        int pageSize = params.pageSize() == null ? 50 : Math.max(1, Math.min(100, params.pageSize()));
        int offset = offset(params.pageToken());
        String state = params.status() == null ? null : params.status().name();
        DataSource dataSource = dataSource(tenantId);
        int total = rows.countOfKey(dataSource, keyId, params.contextId(), state, params.statusTimestampAfter());
        List<Task> tasks = new ArrayList<>();
        for (A2aTaskRepository.Row row : rows.findOfKey(dataSource, keyId, params.contextId(), state,
                params.statusTimestampAfter(), pageSize, offset)) {
            Task task = current(tenantId, dataSource, row);
            tasks.add(Boolean.TRUE.equals(params.includeArtifacts()) ? task
                    : Task.builder(task).artifacts(List.of()).build());
        }
        String next = offset + tasks.size() < total ? String.valueOf(offset + tasks.size()) : "";
        return new ListTasksResult(tasks, total, tasks.size(), next);
    }

    @Override
    public boolean isTaskActive(String taskId) {
        Task task = get(taskId);
        return task != null && !task.status().state().isFinal();
    }

    @Override
    public boolean isTaskFinalized(String taskId) {
        Task task = get(taskId);
        return task != null && task.status().state().isFinal();
    }

    /** The tenants with A2A tasks - for the cleanup. */
    List<Long> tenantsWithTasks() {
        return index.findTenantIds();
    }

    /**
     * Deletes the tenant's tasks last changed before the time, and the ids kept without a task; how many tasks. Ended
     * or not: a question lost to a restart and a run nobody mirrors keep their stored state until someone reads them -
     * they would stay for good.
     */
    int deleteChangedBefore(Long tenantId, Instant before) {
        DataSource dataSource = dataSource(tenantId);
        List<String> deleted = rows.deleteChangedBefore(dataSource, before);
        if (!deleted.isEmpty()) {
            index.deleteByTaskIdIn(deleted);
        }
        forgetIdsWithoutTask(tenantId, dataSource, before);
        return deleted.size();
    }

    /**
     * A plain question's answer names a task id, and the SDK records its owner although no task is ever stored: such
     * index rows go once they are as old as a task may get.
     */
    private void forgetIdsWithoutTask(Long tenantId, DataSource dataSource, Instant before) {
        List<String> old = new ArrayList<>(index.findTaskIdsCreatedBefore(tenantId, before));
        if (old.isEmpty()) {
            return;
        }
        old.removeAll(rows.existing(dataSource, old));
        if (!old.isEmpty()) {
            index.deleteByTaskIdIn(old);
        }
    }

    /** The task as it is now: a run read from the run when nobody mirrors it, a question lost to a restart failed. */
    private Task current(Long tenantId, DataSource dataSource, A2aTaskRepository.Row row) {
        Task task = parse(row.task());
        TaskState state = task.status().state();
        if (state.isFinal()) {
            return task;
        }
        if (row.runId() != null) {
            return fromRun(tenantId, task, row.runId());
        }
        if (row.updatedAt().isBefore(STARTED)) {
            Task failed = Task.builder(task).status(new TaskStatus(TaskState.TASK_STATE_FAILED,
                    RunTasks.agentMessage(RESTARTED), null)).build();
            rows.save(dataSource, failed.id(), row.mcpKeyId(), failed.status().state().name(), failed.contextId(),
                    null, json(failed));
            return failed;
        }
        return task;
    }

    private Task fromRun(Long tenantId, Task task, Long runId) {
        Long scheduledTaskId = scheduledTaskId(task);
        if (scheduledTaskId == null) {
            return task;
        }
        Optional<TaskRun> run = taskService.findRun(tenantId, scheduledTaskId, runId);
        Optional<de.alpdesk.entities.task.Task> scheduled = taskService.taskOfTenant(tenantId, scheduledTaskId);
        if (run.isEmpty() || scheduled.isEmpty()) {
            return task;
        }
        Task.Builder current = Task.builder(task).status(RunTasks.status(scheduled.get(), run.get()));
        if (!run.get().state().open()) {
            current.artifacts(runTasks.artifacts(tenantId, scheduled.get(), run.get()));
        }
        return current.build();
    }

    /** The run's id in our extension's metadata of the task - set when it started. */
    static @Nullable Long runId(Task task) {
        return number(task, "run");
    }

    static @Nullable Long scheduledTaskId(Task task) {
        return number(task, "taskId");
    }

    private static @Nullable Long number(Task task, String key) {
        if (task.metadata() != null && task.metadata().get(A2aCards.ASSISTANT_EXTENSION) instanceof Map<?, ?> ours
                && ours.get(key) instanceof Number value) {
            return value.longValue();
        }
        return null;
    }

    boolean owned(String taskId) {
        return index.existsById(taskId);
    }

    /** The database of the task's tenant - for its webhooks. TaskNotFound for a task of nobody. */
    DataSource dataSourceOfTask(String taskId) {
        A2aTaskIndex owner = index.findById(taskId).orElseThrow(org.a2aproject.sdk.spec.TaskNotFoundError::new);
        return dataSource(owner.tenantId());
    }

    private DataSource dataSource(Long tenantId) {
        Tenant tenant = tenants.findById(tenantId).orElseThrow(() -> new IllegalStateException("Unknown tenant"));
        return databases.dataSource(tenant.databaseName());
    }

    private static int offset(@Nullable String pageToken) {
        try {
            return pageToken == null || pageToken.isBlank() ? 0 : Math.max(0, Integer.parseInt(pageToken));
        } catch (NumberFormatException e) {
            throw new org.a2aproject.sdk.spec.InvalidParamsError("Invalid page token");
        }
    }

    private static String json(Task task) {
        try {
            return JsonUtil.toJson(task);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The A2A task could not be written", e);
        }
    }

    private static Task parse(String json) {
        try {
            return JsonUtil.fromJson(json, Task.class);
        } catch (JsonProcessingException e) {
            throw new IllegalStateException("The A2A task could not be read", e);
        }
    }
}
