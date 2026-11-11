package de.alpdesk.services.task.step;

import de.alpdesk.entities.ai.AiModel;
import de.alpdesk.entities.ai.ModelKind;
import de.alpdesk.entities.task.RunFile;
import de.alpdesk.repositories.task.TaskRunRepository;
import de.alpdesk.services.agent.AgentLoop;
import de.alpdesk.services.agent.AgentRun;
import de.alpdesk.services.ai.ChatOptions;
import de.alpdesk.services.ai.ProviderKeyService;
import de.alpdesk.services.ai.ResolvedKey;
import de.alpdesk.services.ai.TenantModelService;
import de.alpdesk.services.common.ServiceException;
import de.alpdesk.services.document.KnowledgeScope;
import de.alpdesk.services.task.StepContext;
import de.alpdesk.services.task.StepOutcome;
import de.alpdesk.services.task.TaskDefinition.Agent;
import de.alpdesk.services.task.TaskDefinition.CheckPoint;
import de.alpdesk.services.task.TaskDefinition.Severity;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskDefinition.StepType;
import de.alpdesk.services.task.TaskResults;
import de.alpdesk.services.tools.ExploredFile;
import de.alpdesk.services.tools.ReadFileLinesTool;
import de.alpdesk.services.tools.SearchFileTool;
import de.alpdesk.services.tools.ToolContext;
import de.alpdesk.services.tools.Toolbox;
import dev.langchain4j.agent.tool.ToolSpecification;
import dev.langchain4j.data.message.AiMessage;
import dev.langchain4j.data.message.ChatMessage;
import dev.langchain4j.data.message.SystemMessage;
import dev.langchain4j.data.message.UserMessage;
import io.micronaut.context.annotation.Value;
import io.micronaut.http.HttpStatus;
import io.micronaut.json.JsonMapper;
import jakarta.inject.Singleton;

import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * agent and verify (docs/architecture/tasks.md "Steps"): the tenant's chat model checks the lines read against the
 * check points - through the chat's AgentLoop, so the tenant's key, the usage per round and the loop's rules hold here
 * too. Small files go with the question whole; larger ones are read with search_file and read_file_lines, the only
 * tools a step has. The answer must be the structured result; one that is not gets one more round, then the step
 * fails. A verify step gets the findings of the step it verifies and judges each again.
 * <p>
 * Without lines to check, no model is asked: every check point is ok. Not in a transaction - a provider can take
 * minutes.
 */
@Singleton
public class AgentStep {

    private static final Set<String> TOOLS = Set.of(SearchFileTool.NAME, ReadFileLinesTool.NAME);

    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AgentLoop loop;
    private final Toolbox toolbox;
    private final TaskRunRepository runs;
    private final JsonMapper json;
    private final int rounds;
    private final ZoneId zone;

    public AgentStep(TenantModelService models, ProviderKeyService keys, AgentLoop loop, Toolbox toolbox,
                     TaskRunRepository runs, JsonMapper json, @Value("${app.task.agent-rounds}") int rounds,
                     @Value("${app.timezone}") String zone) {
        this.zone = ZoneId.of(zone);
        this.models = models;
        this.keys = keys;
        this.loop = loop;
        this.toolbox = toolbox;
        this.runs = runs;
        this.json = json;
        this.rounds = rounds;
    }

    public StepOutcome run(StepContext context) {
        Step step = context.step();
        Agent agent = step.agent();
        List<StepInputs.Input> inputs = StepInputs.inputs(runs, context, context.step().input());
        List<RunFile> files = StepInputs.files(inputs);
        List<Map<String, Object>> verified = step.type() == StepType.VERIFY
                ? TaskFindings.checks(context.results().get(step.verifies())) : List.of();
        // A report is handed on as the checking step wrote it - there is nothing to confirm or dismiss
        List<Map<String, Object>> reports = TaskFindings.reports(verified);
        List<Map<String, Object>> toJudge = TaskFindings.withoutReports(verified);
        List<CheckPoint> checks = step.type() == StepType.VERIFY
                ? agent.checks().stream().filter(check -> check.severity() != Severity.REPORT).toList()
                : agent.checks();
        if (files.isEmpty() && step.type() == StepType.AGENT) {
            return StepOutcome.of(TaskFindings.nothingToCheck(agent.checks()));
        }
        if (step.type() == StepType.VERIFY && nothingFound(toJudge)) {
            return StepOutcome.of(TaskFindings.counted(verified));
        }
        AiModel model = models.requireUsable(context.tenant().id(), agent.modelId(), ModelKind.CHAT);
        ResolvedKey key = keys.resolve(context.tenant(), model);
        ToolContext tools = new ToolContext(context.tenant(), ToolContext.Asker.TASK, model, null,
                KnowledgeScope.companyKnowledgeAnd(List.of()), context.dataSource(), null, false, 0,
                ToolContext.Files.NONE);
        tools.onlyTools(TOOLS);
        tools.stopWhen(() -> runs.cancelRequested(context.dataSource(), context.runId()));
        tools.countInto(context.spent());
        String question = question(context, checks, toJudge) + StepInputs.filesText(inputs, tools, model.callsTools());
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from(TaskPrompts.system(step.type())),
                UserMessage.from(question)));
        List<ToolSpecification> specifications = toolbox.specificationsFor(tools);
        String answer = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                specifications, tools, rounds));
        Map<String, Object> result;
        try {
            result = TaskFindings.read(answer, checks, files, json);
        } catch (TaskFindings.Invalid first) {
            messages.add(AiMessage.from(answer));
            messages.add(UserMessage.from(TaskPrompts.again(first.getMessage())));
            String again = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                    specifications, tools, 2));
            try {
                result = TaskFindings.read(again, checks, files, json);
            } catch (TaskFindings.Invalid second) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.resultInvalid", model.displayName()
                        + " gave no valid result: " + second.getMessage(), Map.of("model", model.displayName(),
                        "problem", second.getMessage()));
            }
        }
        if (!reports.isEmpty()) {
            result = TaskFindings.withReports(result, reports, agent.checks());
        }
        return StepOutcome.of(result);
    }

    private String question(StepContext context, List<CheckPoint> checks, List<Map<String, Object>> verified) {
        Step step = context.step();
        StringBuilder question = new StringBuilder("Task \"").append(context.task().name()).append("\", step \"")
                .append(step.id()).append("\", round ").append(context.round()).append(". ")
                // the files end where they were read - without the time, "nothing since 03:00" could not be told apart
                // from "nothing in the last minutes"
                .append(TaskPrompts.now(ZonedDateTime.now(zone)));
        if (step.agent().instructions() != null) {
            question.append("\n\n").append(step.agent().instructions());
        }
        question.append(TaskPrompts.checkPoints(checks));
        if (step.type() == StepType.VERIFY) {
            question.append(TaskPrompts.findings("=== The findings to verify ===", verified));
        } else if (context.round() > 1) {
            for (Step verifier : context.task().steps()) {
                if (step.id().equals(verifier.verifies())) {
                    question.append(TaskPrompts.findings(TaskPrompts.NOTES,
                            TaskFindings.checks(context.results().get(verifier.id()))));
                }
            }
        }
        return question.toString();
    }

    private static boolean nothingFound(List<Map<String, Object>> checks) {
        return checks.stream().allMatch(check -> TaskFindings.STATUS_OK.equals(check.get("status")));
    }
}
