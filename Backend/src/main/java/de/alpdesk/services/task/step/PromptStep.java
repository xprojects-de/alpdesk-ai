package de.alpdesk.services.task.step;

import de.alpdesk.entities.mcp.McpConnection;
import de.alpdesk.services.a2a.AgentAnswers;
import de.alpdesk.services.assistant.AssistantService;
import de.alpdesk.services.document.KnowledgeAreaService;
import de.alpdesk.services.mcp.McpConnectionService;
import de.alpdesk.services.task.TaskDefinition.AssistantAsk;
import de.alpdesk.services.tools.ReadDocumentTool;
import de.alpdesk.services.tools.SimilaritySearchTool;
import de.alpdesk.services.tools.WebSearchTool;
import java.util.HashSet;
import java.util.Optional;
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
import de.alpdesk.services.task.TaskDefinition.Edge;
import de.alpdesk.services.task.TaskDefinition.Step;
import de.alpdesk.services.task.TaskDefinition.StepType;
import de.alpdesk.services.task.TaskResults;
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
import io.micronaut.core.annotation.Nullable;
import io.micronaut.http.HttpStatus;
import jakarta.inject.Singleton;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * prompt (docs/architecture/tasks.md "The step prompt"): the tenant's chat model answers the step's prompt in free
 * text, from what its inputs gave - the lines files read, the answers of earlier prompts, the findings of agent and
 * verify steps. With choices it also picks one word of them on the answer's last line ("CHOICE: rework"), which a
 * condition reads - a condition never reads the free text. The answer is stored as a run file, so the next step reads
 * it as it reads a log file. Sent back by an edge, its next round sees the answer of the step that sent it back.
 * <p>
 * Through the chat's AgentLoop like the agent step. Not in a transaction - a provider can take minutes.
 */
@Singleton
public class PromptStep {

    static final int MAX_ANSWER = 20_000;
    private static final Set<String> TOOLS = Set.of(SearchFileTool.NAME, ReadFileLinesTool.NAME);
    private static final Pattern CHOICE_LINE = Pattern.compile("(?i)^\\s*CHOICE:\\s*([A-Za-z0-9_-]+)\\s*$");

    private final TenantModelService models;
    private final ProviderKeyService keys;
    private final AgentLoop loop;
    private final Toolbox toolbox;
    private final TaskRunRepository runs;
    private final McpConnectionService connections;
    private final AgentAnswers agentAnswers;
    private final AssistantService assistants;
    private final KnowledgeAreaService areas;
    private final int rounds;

    public PromptStep(TenantModelService models, ProviderKeyService keys, AgentLoop loop, Toolbox toolbox,
                      TaskRunRepository runs, McpConnectionService connections, AgentAnswers agentAnswers,
                      AssistantService assistants, KnowledgeAreaService areas, @Value("${app.task.agent-rounds}") int rounds) {
        this.connections = connections;
        this.agentAnswers = agentAnswers;
        this.assistants = assistants;
        this.areas = areas;
        this.models = models;
        this.keys = keys;
        this.loop = loop;
        this.toolbox = toolbox;
        this.runs = runs;
        this.rounds = rounds;
    }

    public StepOutcome run(StepContext context) {
        Step step = context.step();
        AssistantAsk assistant = step.assistant();
        if (assistant != null) {
            Optional<McpConnection> agent = connections.answeringAgent(context.tenant().id());
            if (agent.isPresent()) {
                return answeredByAgent(context, agent.get());
            }
        }
        if (assistant != null) {
            requireStillThere(context, assistant);
        }
        if (step.agent().modelId() == null) {
            // Saved while the tenant answered through its agent, which is no longer so.
            throw new ServiceException(HttpStatus.CONFLICT, "task.modelRequiredWithoutAgent", "A model is needed - "
                    + "only a tenant answering through its A2A agent leaves it out");
        }
        AiModel model = models.requireUsable(context.tenant().id(), step.agent().modelId(), ModelKind.CHAT);
        ResolvedKey key = keys.resolve(context.tenant(), model);
        // As many hits as the tenant's chat (or the step's template) - 0 would let the knowledge search find nothing.
        int searchResults = assistant == null ? 0
                : assistants.forQuestion(context.tenant().id(), assistant.templateId()).searchResults();
        ToolContext tools = new ToolContext(context.tenant(), ToolContext.Asker.TASK, model, null,
                KnowledgeScope.companyKnowledgeAnd(assistant == null ? List.of() : assistant.areaIds()),
                context.dataSource(), null, false, searchResults, ToolContext.Files.NONE);
        if (assistant == null) {
            tools.onlyTools(TOOLS);
        } else {
            // The assistant's way: the knowledge, the software named, the web if asked - nothing that changes or
            // creates (no sandbox, no files: the answer stays text).
            Set<String> allowed = new HashSet<>(TOOLS);
            allowed.addAll(Set.of(SimilaritySearchTool.NAME, ReadDocumentTool.NAME));
            if (assistant.web()) {
                allowed.add(WebSearchTool.NAME);
            }
            tools.onlyTools(allowed);
            tools.searchesWeb(assistant.web());
            tools.taskConnections(assistant.connectionIds());
        }
        tools.stopWhen(() -> runs.cancelRequested(context.dataSource(), context.runId()));
        tools.countInto(context.spent());
        String question = question(context) + StepInputs.filesText(StepInputs.inputs(runs, context, step.input()), tools,
                model.callsTools()) + sentBack(context);
        List<ChatMessage> messages = new ArrayList<>(List.of(SystemMessage.from(TaskPrompts.prompt(step.choices())
                + assistantRules(context, assistant)), UserMessage.from(question)));
        List<ToolSpecification> specifications = toolbox.specificationsFor(tools);
        String answer = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                specifications, tools, rounds));
        String choice = null;
        if (!step.choices().isEmpty()) {
            choice = choice(answer, step.choices());
            if (choice == null) {
                messages.add(AiMessage.from(answer));
                messages.add(UserMessage.from(TaskPrompts.chooseAgain(step.choices())));
                answer = loop.run(new AgentRun(context.tenant(), null, model, key, ChatOptions.NONE, messages,
                        specifications, tools, 2));
                choice = choice(answer, step.choices());
            }
            if (choice == null) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.choiceInvalid", model.displayName()
                        + " picked none of " + String.join(", ", step.choices()),
                        Map.of("model", model.displayName(), "choices", String.join(", ", step.choices())));
            }
        }
        return StepOutcome.of(result(context, withoutChoice(answer), choice));
    }

    /**
     * The areas, software and template the step was checked with, still there - and an A2A agent among the software
     * still allowed: its tool is not offered once the admin took A2A away. Else the step fails rather than answer
     * without them while the run reports DONE.
     */
    private void requireStillThere(StepContext context, AssistantAsk assistant) {
        Long tenantId = context.tenant().id();
        if (!context.tenant().a2aAllowed()
                && assistant.connectionIds().stream().anyMatch(id -> connections.isAgent(tenantId, id))) {
            throw ServiceException.conflict("task.agentUnavailable",
                    "The task's A2A agent is gone, or the tenant may no longer use A2A agents");
        }
        boolean gone = !areas.namesById(tenantId).keySet().containsAll(assistant.areaIds())
                || assistant.connectionIds().stream().anyMatch(id -> !connections.exists(tenantId, id))
                || (assistant.templateId() != null && assistants.templates(tenantId).stream()
                        .noneMatch(template -> template.id().equals(assistant.templateId())));
        if (gone) {
            throw ServiceException.conflict("task.assistantGone", "A knowledge area, connected software or chat "
                    + "template the step names was deleted - check the task again");
        }
    }

    /** For assistant: true - the knowledge and software to use, and the template's instructions; nothing otherwise. */
    private String assistantRules(StepContext context, @Nullable AssistantAsk assistant) {
        if (assistant == null) {
            return "";
        }
        StringBuilder rules = new StringBuilder("\n\n").append(TaskPrompts.ASSISTANT);
        if (assistant.templateId() != null) {
            assistants.templates(context.tenant().id()).stream()
                    .filter(template -> template.id().equals(assistant.templateId())).findFirst()
                    .ifPresent(template -> rules.append("\n\n").append(template.instructions()));
        }
        return rules.toString();
    }

    /**
     * A tenant answering through its A2A agent: the step's question goes to the agent - the areas, software and web of
     * the step are the agent's matter. A question back fails the step: a task has no person to answer. No usage of ours.
     */
    private StepOutcome answeredByAgent(StepContext context, McpConnection agent) {
        if (!context.tenant().a2aAllowed()) {
            throw ServiceException.conflict("a2a.notAllowed",
                    "The company's chat answers through an A2A agent the admin no longer allows");
        }
        Step step = context.step();
        // The step's prompt and what it reads - not the rules our model gets: the agent has its own. Task and run go in
        // the metadata; the choices are asked for as the answer's last line.
        String question = step.agent().instructions() + findings(context)
                + StepInputs.filesText(StepInputs.inputs(runs, context, step.input()), null, false) + sentBack(context)
                + TaskPrompts.agentChoice(step.choices());
        Map<String, Object> asker = new LinkedHashMap<>();
        asker.put("tenant", context.tenant().name());
        asker.put("language", context.tenant().searchLanguage().name().toLowerCase(Locale.ROOT));
        asker.put("task", context.task().name());
        asker.put("run", context.runId());
        asker.put("skills", connections.skillsOf(agent));
        AgentAnswers.Result answer = agentAnswers.ask(context.tenant().id(), new AgentAnswers.Question(agent,
                question, List.of(), asker, null, null, new AgentAnswers.Call(), null), AgentAnswers.Listener.NONE);
        if (answer.waiting()) {
            throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.agentAsksBack", agent.name()
                    + " asks back - a task has no person to answer: " + answer.text(), Map.of("agent", agent.name()));
        }
        String choice = null;
        if (!step.choices().isEmpty()) {
            choice = choice(answer.text(), step.choices());
            if (choice == null) {
                throw new ServiceException(HttpStatus.BAD_GATEWAY, "task.choiceInvalid", agent.name()
                        + " picked none of " + String.join(", ", step.choices()),
                        Map.of("model", agent.name(), "choices", String.join(", ", step.choices())));
            }
        }
        return StepOutcome.of(result(context, withoutChoice(answer.text()), choice));
    }

    private static String question(StepContext context) {
        Step step = context.step();
        return "Task \"" + context.task().name() + "\", step \"" + step.id() + "\", round " + context.round() + ".\n\n"
                + step.agent().instructions() + findings(context);
    }

    /** The findings of the agent and verify steps it reads. */
    private static String findings(StepContext context) {
        StringBuilder text = new StringBuilder();
        for (String input : context.step().input()) {
            List<Map<String, Object>> checks = TaskFindings.checks(context.results().get(input));
            if (!checks.isEmpty()) {
                text.append(TaskPrompts.findings("=== The findings of \"" + input + "\" ===", checks));
            }
        }
        return text.toString();
    }

    /** Sent back by an edge: the answers of the steps whose edges lead back here - why it is asked again. */
    private String sentBack(StepContext context) {
        if (context.round() < 2) {
            return "";
        }
        List<String> senders = new ArrayList<>();
        for (Step other : context.task().steps()) {
            boolean leadsHere = other.next().stream().map(Edge::to).anyMatch(context.step().id()::equals);
            if (leadsHere && other.type() == StepType.PROMPT && context.results().containsKey(other.id())) {
                senders.add(other.id());
            }
        }
        StringBuilder text = new StringBuilder();
        for (RunFile file : StepInputs.files(runs, context, senders)) {
            text.append("\n\n").append(TaskPrompts.SENT_BACK).append(" (\"").append(file.name()).append("\") ===\n")
                    .append(String.join("\n", file.lines().stream().map(RunFile.Line::text).toList()));
        }
        return text.toString();
    }

    /** The word of the answer's last line that is not empty - when it is one of the choices. */
    static @Nullable String choice(String answer, List<String> choices) {
        String[] lines = answer.strip().split("\n");
        Matcher match = CHOICE_LINE.matcher(lines[lines.length - 1]);
        if (!match.matches()) {
            return null;
        }
        String word = match.group(1).toLowerCase(Locale.ROOT);
        return choices.contains(word) ? word : null;
    }

    static String withoutChoice(String answer) {
        String[] lines = answer.strip().split("\n");
        if (lines.length > 0 && CHOICE_LINE.matcher(lines[lines.length - 1]).matches()) {
            return String.join("\n", List.of(lines).subList(0, lines.length - 1)).strip();
        }
        return answer.strip();
    }

    /** The answer as a run file of its own, numbered like a file read - the next steps read it, the state keeps its id. */
    private Map<String, Object> result(StepContext context, String answer, @Nullable String choice) {
        boolean truncated = answer.length() > MAX_ANSWER;
        String kept = truncated ? answer.substring(0, MAX_ANSWER) : answer;
        List<RunFile.Line> lines = new ArrayList<>();
        long number = 0;
        for (String line : kept.split("\n", -1)) {
            lines.add(new RunFile.Line(++number, line));
        }
        Long fileId = runs.saveFile(context.dataSource(), context.runId(), context.step().id(),
                context.step().id() + ".txt", lines);
        Map<String, Object> result = new LinkedHashMap<>();
        result.put(TaskResults.CHARS, kept.length());
        if (choice != null) {
            result.put(TaskResults.CHOICE, choice);
        }
        result.put(TaskResults.FILE_IDS, List.of(fileId));
        result.put(TaskResults.TRUNCATED, truncated);
        return result;
    }
}
