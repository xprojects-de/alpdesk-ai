package de.alpdesk.services.task.step;

import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.a2a.A2aClient;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.task.TaskDefinition.CallAgent;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskResults;
import io.micronaut.context.annotation.Value;
import io.micronaut.core.annotation.Nullable;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;
import org.a2aproject.sdk.spec.DataPart;
import org.a2aproject.sdk.spec.Part;
import org.a2aproject.sdk.spec.TextPart;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * call_agent (docs/architecture/tasks.md "call_agent"): the clear rows of the step's extract step sent to an A2A agent
 * of the tenant's connected software - a message per row, or one with every row - as call_tool sends them to a tool: the
 * prompt as text, the row's fields, its source and the fixed values as data. A Task the agent answers with is waited
 * for up to app.a2a.timeout. The agent asking back fails the row (a task has no person to answer), as a refused call
 * does; the run then ends unclear and mails the rows. The agent out of reach marks every row still to go, unsent.
 * Repeats are the agent's matter - every row carries its source. Not in a transaction: an agent may take minutes.
 */
@Singleton
public class CallAgentStep {

    /** The agent's answer kept per call at most - shown in the run view, as call_tool's. */
    static final int MAX_ANSWER = 2000;
    /** A row's own fault: the agent answered with an error. Anything else is the agent as a whole. */
    private static final Set<String> ROW_REFUSED = Set.of("mcpServer.error", "mcpServer.requestFailed");

    private final McpConnectionService connections;
    private final A2aClient a2a;
    private final TaskRunRepository runs;
    private final JsonMapper json;
    private final Duration timeout;

    public CallAgentStep(McpConnectionService connections, A2aClient a2a, TaskRunRepository runs, JsonMapper json,
                         @Value("${app.a2a.timeout}") Duration timeout) {
        this.connections = connections;
        this.a2a = a2a;
        this.runs = runs;
        this.json = json;
        this.timeout = timeout;
    }

    public StepOutcome run(StepContext context) {
        CallAgent settings = context.step().callAgent();
        String input = context.step().input().getFirst();
        List<StepRows.Row> clear = new ArrayList<>();
        for (StepRows.Row row : StepRows.of(runs, json, context, input)) {
            if (row.row().unclear() == null) {
                clear.add(row);
            }
        }
        List<Map<String, Object>> calls = new ArrayList<>();
        // Asked for only when there is something to send: a run whose rows are all unclear needs no agent.
        McpConnection agent = clear.isEmpty() ? null : connections.agentForRun(context.tenant().id(), settings.connectionId());
        if (settings.all() && !clear.isEmpty()) {
            List<Map<String, Object>> rows = new ArrayList<>();
            clear.forEach(row -> rows.add(data(row, Map.of())));
            Map<String, Object> data = new LinkedHashMap<>(settings.with());
            data.put("rows", rows);
            calls.add(call(context, agent, settings, data, clear.size() + " rows").entry());
        } else if (!settings.all()) {
            String agentDown = null;
            for (StepRows.Row row : clear) {
                if (runs.cancelRequested(context.dataSource(), context.runId())) {
                    throw ServiceException.conflict("task.cancelled", "The run was cancelled");
                }
                if (agentDown != null) {
                    calls.add(entry(row.row().source(), true, agentDown));
                    continue;
                }
                Call call = call(context, agent, settings, data(row, settings.with()), row.row().source());
                calls.add(call.entry());
                agentDown = call.agentDown();
            }
        }
        int failed = (int) calls.stream().filter(call -> Boolean.TRUE.equals(call.get("failed"))).count();
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.SENT, calls.size() - failed);
        result.put(TaskResults.FAILED, failed);
        result.put(TaskResults.CALLS, calls);
        return StepOutcome.of(result);
    }

    /** A row's fields by their names, its source and the fixed values. */
    private static Map<String, Object> data(StepRows.Row row, Map<String, String> with) {
        Map<String, Object> data = new LinkedHashMap<>(row.values());
        data.put("source", row.row().source());
        data.putAll(with);
        return data;
    }

    private record Call(Map<String, Object> entry, @Nullable String agentDown) {
    }

    /**
     * One message and what came of it - a failure kept with the call, not thrown: the other rows still go. The agent
     * failing as a whole (out of reach, no answer in time, our key refused) is said too: further rows are marked with it.
     */
    private Call call(StepContext context, McpConnection agent, CallAgent settings, Map<String, Object> data,
                      String source) {
        List<Part<?>> parts = List.of(new TextPart(settings.prompt() != null ? settings.prompt()
                : "Row of " + context.task().name()), new DataPart(data));
        try {
            A2aClient.Reply reply = a2a.send(agent, connections.credential(agent), parts, asker(context, settings), timeout);
            if (reply.asksBack()) {
                return new Call(entry(source, true, "task.agentAsksBack: the agent asks back - a task has no person "
                        + "to answer: " + reply.result().text()), null);
            }
            return new Call(entry(source, reply.result().error(), reply.result().text()), null);
        } catch (ServiceException e) {
            if (e.code().equals("a2a.taskTimeout")) {
                return new Call(entry(source, true, "task.agentTimeout: " + e.getMessage()), null);
            }
            String agentDown = ROW_REFUSED.contains(e.code()) ? null : e.getMessage();
            return new Call(entry(source, true, e.getMessage()), agentDown);
        }
    }

    /** Who asks: the tenant, the task and its run, the one skill - no user, no groups: a task has no person. */
    private static Map<String, Object> asker(StepContext context, CallAgent settings) {
        Map<String, Object> asker = new LinkedHashMap<>();
        asker.put("tenant", context.tenant().name());
        asker.put("language", context.tenant().searchLanguage().name().toLowerCase());
        asker.put("task", context.task().name());
        asker.put("run", context.runId());
        asker.put("skills", settings.skill() == null ? List.of() : List.of(settings.skill()));
        return asker;
    }

    private static Map<String, Object> entry(String source, boolean failed, String answer) {
        Map<String, Object> call = new LinkedHashMap<>();
        call.put("source", source);
        call.put("failed", failed);
        call.put("answer", answer.length() > MAX_ANSWER ? answer.substring(0, MAX_ANSWER) + " [cut off]" : answer);
        return call;
    }
}
