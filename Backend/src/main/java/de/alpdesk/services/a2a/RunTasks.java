package de.alpdesk.services.a2a;

import de.alpdesk.entities.task.RunState;
import de.alpdesk.entities.task.Task;
import de.alpdesk.entities.task.TaskRun;
import de.alpdesk.services.mcp.TaskMcpTools;
import de.alpdesk.services.task.TaskAnswer;
import de.alpdesk.services.task.TaskService;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.spec.Artifact;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Message;
import org.a2aproject.sdk.spec.TaskState;
import org.a2aproject.sdk.spec.TaskStatus;
import org.a2aproject.sdk.spec.TextPart;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

/**
 * A run of a scheduled task as an A2A task (docs/architecture/a2a.md "Tasks over A2A"): its state mapped, and once it
 * ended the artifacts "report" (the text MCP's task_run gives) and "findings" (the same as data) - never the lines read.
 */
@Singleton
public class RunTasks {

    private final TaskService tasks;
    private final TaskMcpTools describer;

    public RunTasks(TaskService tasks, TaskMcpTools describer) {
        this.tasks = tasks;
        this.describer = describer;
    }

    /** QUEUED submitted, RUNNING working, DONE and UNCLEAR completed, FAILED failed, CANCELLED canceled. */
    static TaskState stateOf(RunState state) {
        return switch (state) {
            case QUEUED -> TaskState.TASK_STATE_SUBMITTED;
            case RUNNING -> TaskState.TASK_STATE_WORKING;
            case DONE, UNCLEAR -> TaskState.TASK_STATE_COMPLETED;
            case FAILED -> TaskState.TASK_STATE_FAILED;
            case CANCELLED -> TaskState.TASK_STATE_CANCELED;
        };
    }

    /** The status message of the run's state: its error, "ended unclear", or nothing. */
    static String statusText(Task task, TaskRun run) {
        return switch (run.state()) {
            case QUEUED, RUNNING -> "Run " + run.id() + " of " + task.name() + " started";
            case UNCLEAR -> "ended unclear - a finding stayed open";
            case FAILED -> run.errorMessage() == null ? "failed" : run.errorMessage();
            case DONE -> "Run " + run.id() + " of " + task.name() + " done";
            case CANCELLED -> "cancelled";
        };
    }

    static Message agentMessage(String text) {
        return Message.builder().role(Message.Role.ROLE_AGENT).parts(new TextPart(text))
                .messageId(UUID.randomUUID().toString()).build();
    }

    static TaskStatus status(Task task, TaskRun run) {
        return new TaskStatus(stateOf(run.state()), agentMessage(statusText(task, run)), null);
    }

    /** The ended run's artifacts: "report" as text, "findings" as data. */
    List<Artifact> artifacts(Long tenantId, Task task, TaskRun run) {
        Map<String, Object> data = new LinkedHashMap<>();
        List<Map<String, Object>> findings = new ArrayList<>();
        for (TaskService.Finding finding : tasks.findings(tenantId, run)) {
            List<Map<String, Object>> evidence = new ArrayList<>();
            finding.evidence().forEach(line -> evidence.add(Map.of("file", line.file(), "line", line.line(),
                    "text", line.text())));
            findings.add(Map.of("step", finding.step(), "checkPoint", finding.checkPoint(),
                    "severity", finding.severity(), "status", finding.status(), "note", finding.note(),
                    "evidence", evidence));
        }
        List<Map<String, Object>> rows = new ArrayList<>();
        for (TaskService.Row row : tasks.rows(tenantId, run)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", row.step());
            entry.put("source", row.source());
            entry.put("values", row.values());
            if (row.unclear() != null) {
                entry.put("unclear", row.unclear());
            }
            rows.add(entry);
        }
        List<Map<String, Object>> handedOn = new ArrayList<>();
        for (TaskAnswer answer : tasks.handedOn(tenantId, run)) {
            Map<String, Object> entry = new LinkedHashMap<>();
            entry.put("step", answer.step());
            entry.put("round", answer.round());
            if (answer.choice() != null) {
                entry.put("choice", answer.choice());
            }
            entry.put("text", answer.text());
            handedOn.add(entry);
        }
        data.put("findings", findings);
        data.put("rows", rows);
        data.put("handedOn", handedOn);
        return List.of(
                new Artifact("report", "report", null, List.of(new TextPart(describer.describe(tenantId, task, run))),
                        null, List.of(A2aCards.ASSISTANT_EXTENSION)),
                new Artifact("findings", "findings", null, List.of(new DataPart(data)), null,
                        List.of(A2aCards.ASSISTANT_EXTENSION)));
    }
}
