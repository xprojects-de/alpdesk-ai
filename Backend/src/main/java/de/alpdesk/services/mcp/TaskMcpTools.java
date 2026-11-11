package de.alpdesk.services.mcp;

import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.task.TaskAnswer;
import de.alpdesk.services.task.TaskService;
import io.micronaut.context.annotation.Factory;
import io.micronaut.context.annotation.Value;
import io.modelcontextprotocol.common.McpTransportContext;
import io.modelcontextprotocol.json.McpJsonMapper;
import io.modelcontextprotocol.server.McpStatelessServerFeatures.SyncToolSpecification;
import io.modelcontextprotocol.spec.McpSchema;
import jakarta.inject.Singleton;

import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/**
 * The scheduled tasks on the tenant's MCP server (docs/architecture/tasks.md "MCP"): an MCP client lists and starts the
 * tasks a tenant admin assigned to its MCP key - no other. run_task waits a while for the run's end, so a quick task answers in
 * the same call; a longer one answers with its run's id, which task_run asks for.
 */
@Factory
public class TaskMcpTools {

    static final String LIST_TASKS = "list_tasks";
    static final String RUN_TASK = "run_task";
    static final String TASK_RUN = "task_run";
    private static final McpSchema.ToolAnnotations READ_ONLY = new McpSchema.ToolAnnotations(null, true, false, null, null, null);
    private static final String RUN_SCHEMA = """
            {
              "type": "object",
              "properties": {"task": {"type": "string", "description": "The task's name (see list_tasks)"}},
              "required": ["task"]
            }""";
    private static final String RUN_OF_SCHEMA = """
            {
              "type": "object",
              "properties": {
                "task": {"type": "string", "description": "The task's name"},
                "run": {"type": "integer", "description": "The run's id run_task answered with"}
              },
              "required": ["task", "run"]
            }""";
    private static final String NO_ARGUMENTS_SCHEMA = """
            {"type": "object", "properties": {}}""";

    private final TaskService tasks;
    private final McpJsonMapper json;
    private final Duration wait;

    public TaskMcpTools(TaskService tasks, McpJsonMapper json, @Value("${app.task.mcp-wait:60s}") Duration wait) {
        this.tasks = tasks;
        this.json = json;
        this.wait = wait;
    }

    @Singleton
    SyncToolSpecification listTasks() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(LIST_TASKS)
                .description("Lists the company's scheduled tasks you may start - checks of files that report what "
                        + "they find - with the state of each one's last run.")
                .inputSchema(json, NO_ARGUMENTS_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool)
                .callHandler(AssistantMcpTools.guarded(LIST_TASKS, (context, request) ->
                        text(list(AssistantMcpTools.tenantId(context), AssistantMcpTools.mcpKeyId(context)), false)))
                .build();
    }

    @Singleton
    SyncToolSpecification runTask() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(RUN_TASK)
                .description("Starts a run of one of the company's scheduled tasks (see list_tasks) and waits up to a "
                        + "minute for what it found. A longer run answers with its id - ask for it with task_run.")
                .inputSchema(json, RUN_SCHEMA)
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler(AssistantMcpTools.guarded(RUN_TASK, this::run)).build();
    }

    @Singleton
    SyncToolSpecification taskRun() {
        McpSchema.Tool tool = McpSchema.Tool.builder()
                .name(TASK_RUN)
                .description("A run of one of the company's scheduled tasks: whether it has ended, and what it found.")
                .inputSchema(json, RUN_OF_SCHEMA)
                .annotations(READ_ONLY)
                .build();
        return SyncToolSpecification.builder().tool(tool).callHandler(AssistantMcpTools.guarded(TASK_RUN, this::runOf)).build();
    }

    private String list(Long tenantId, Long mcpKeyId) {
        List<Task> opened = tasks.tasksOfMcpKey(tenantId, mcpKeyId);
        if (opened.isEmpty()) {
            return "No task is opened to you.";
        }
        StringBuilder text = new StringBuilder("Tasks:");
        for (Task task : opened) {
            text.append("\n- ").append(task.name()).append(task.active() ? "" : " (switched off)");
            tasks.lastRun(tenantId, task.id()).ifPresent(run -> text.append(" - last run ").append(run.state().name())
                    .append(" at ").append(run.createdAt()));
        }
        return text.toString();
    }

    /** A refusal (unknown task, one still running, switched off, the tenant may not) is a tool error the model reads. */
    private McpSchema.CallToolResult run(McpTransportContext context, McpSchema.CallToolRequest request) {
        Long tenantId = AssistantMcpTools.tenantId(context);
        Long mcpKeyId = AssistantMcpTools.mcpKeyId(context);
        Optional<Task> task = task(tenantId, mcpKeyId, request.arguments());
        if (task.isEmpty()) {
            return text(unknown(tenantId, mcpKeyId), true);
        }
        try {
            TaskRun run = tasks.startByMcp(tenantId, task.get());
            return text(describe(tenantId, task.get(), awaitEnd(tenantId, run)), false);
        } catch (ServiceException e) {
            return text(e.getMessage(), true);
        }
    }

    private McpSchema.CallToolResult runOf(McpTransportContext context, McpSchema.CallToolRequest request) {
        Long tenantId = AssistantMcpTools.tenantId(context);
        Long mcpKeyId = AssistantMcpTools.mcpKeyId(context);
        Map<String, Object> arguments = request.arguments() == null ? Map.of() : request.arguments();
        Optional<Task> task = task(tenantId, mcpKeyId, arguments);
        if (task.isEmpty()) {
            return text(unknown(tenantId, mcpKeyId), true);
        }
        Optional<TaskRun> run = arguments.get("run") instanceof Number id
                ? tasks.findRun(tenantId, task.get().id(), id.longValue()) : Optional.empty();
        return run.map(found -> text(describe(tenantId, task.get(), found), false))
                .orElseGet(() -> text("There is no such run of " + task.get().name() + ".", true));
    }

    /** The run when it has ended - or as it stands after the wait. */
    private TaskRun awaitEnd(Long tenantId, TaskRun run) {
        long deadline = System.nanoTime() + wait.toNanos();
        TaskRun current = run;
        while (current.state().open() && System.nanoTime() < deadline) {
            try {
                Thread.sleep(500);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                return current;
            }
            current = tasks.findRun(tenantId, run.taskId(), run.id()).orElse(current);
        }
        return current;
    }

    /** A run in words: its end, the answers handed on, the rows, the findings with their evidence - not the lines read. */
    public String describe(Long tenantId, Task task, TaskRun run) {
        if (run.state().open()) {
            return "Run " + run.id() + " of " + task.name() + " is still " + run.state().name()
                    + " - ask for it with task_run (task \"" + task.name() + "\", run " + run.id() + ").";
        }
        StringBuilder text = new StringBuilder("Run ").append(run.id()).append(" of ").append(task.name())
                .append(" ended ").append(run.state().name());
        if (run.errorMessage() != null) {
            text.append(": ").append(run.errorMessage());
        }
        for (TaskAnswer answer : tasks.handedOn(tenantId, run)) {
            text.append("\n\n").append(answer.step()).append(answer.choice() == null ? "" : " (" + answer.choice() + ")")
                    .append(":\n").append(answer.text());
        }
        List<TaskService.Row> rows = tasks.rows(tenantId, run);
        if (!rows.isEmpty()) {
            text.append("\n\nRows taken out of documents (").append(rows.size()).append("):");
            for (TaskService.Row row : rows) {
                text.append("\n- ").append(row.source()).append(": ").append(row.values());
                if (row.unclear() != null) {
                    text.append(" - unclear: ").append(row.unclear());
                }
            }
        }
        List<TaskService.Finding> findings = tasks.findings(tenantId, run);
        text.append(findings.isEmpty() ? " - nothing to report." : " - " + findings.size() + " check point(s) need attention:");
        for (TaskService.Finding finding : findings) {
            text.append("\n- ").append(finding.checkPoint()).append(" (").append(finding.severity().toLowerCase())
                    .append(", ").append(finding.status()).append("): ").append(finding.note());
            for (TaskService.Evidence line : finding.evidence()) {
                text.append("\n  ").append(line.file()).append(" ").append(line.line()).append(": ").append(line.text());
            }
        }
        return text.toString();
    }

    private Optional<Task> task(Long tenantId, Long mcpKeyId, Map<String, Object> arguments) {
        return arguments != null && arguments.get("task") instanceof String name && !name.isBlank()
                ? tasks.mcpTask(tenantId, mcpKeyId, name) : Optional.empty();
    }

    private String unknown(Long tenantId, Long mcpKeyId) {
        List<String> names = tasks.tasksOfMcpKey(tenantId, mcpKeyId).stream().map(Task::name).toList();
        return names.isEmpty() ? "No task is opened to you." : "Unknown task - the tasks: " + String.join(", ", names);
    }

    private static McpSchema.CallToolResult text(String text, boolean isError) {
        return McpSchema.CallToolResult.builder().addTextContent(text).isError(isError).build();
    }
}
